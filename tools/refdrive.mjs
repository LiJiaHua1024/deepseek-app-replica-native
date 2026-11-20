#!/usr/bin/env node
// tools/refdrive.mjs <serial> <pkg> <scene> <out.png>
// Drives the reference (WebView) app into a scene via Chrome DevTools Protocol,
// proxying all api.deepseek.com calls through the shared mock at 127.0.0.1:8321.
// Captures a screenshot with adb exec-out at the scene's capture point.
import { execFileSync, execFile } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { writeFileSync } from 'node:fs'
import http from 'node:http'

const [serial, appId, scene, out] = process.argv.slice(2)
const MOCK = { host: '127.0.0.1', port: 8321 }
const adb = (...args) =>
  execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8', timeout: 20000 }).trim()
const screencap = (file) =>
  writeFileSync(file, execFileSync('adb', ['-s', serial, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 }))

function webViewOrigin() {
  try {
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/ds-dump.xml')
    const xml = adb('shell', 'cat', '/data/local/tmp/ds-dump.xml')
    const node = xml.match(/<node[^>]*class="android\.webkit\.WebView"[^>]*bounds="\[(-?\d+),(-?\d+)\]/) ??
      xml.match(/<node[^>]*bounds="\[(-?\d+),(-?\d+)\]/)
    return node ? { x: Number(node[1]), y: Number(node[2]) } : { x: 0, y: 0 }
  } catch { return { x: 0, y: 0 } }
}

async function tapElement(evaluate, sel) {
  const rect = await evaluate(`(() => { const el = document.querySelector(${JSON.stringify(sel)}); if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 } })()`)
  if (!rect) throw new Error(`no element ${sel}`)
  const o = webViewOrigin()
  const dpr = await evaluate('devicePixelRatio')
  adb('shell', 'input', 'tap', String(Math.round(rect.x * dpr + o.x)), String(Math.round(rect.y * dpr + o.y)))
}

async function launch() {
  adb('shell', 'am', 'start', '-n', `${appId}/io.github.lijiahua1024.dsreplica.MainActivity`)
  const pid = await until('app process', () => adb('shell', `pidof ${appId} || true`))
  let port
  for (let i = 0; i < 40; i++) {
    try {
      port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`)
      if (port) break
    } catch {}
    await delay(250)
  }
  const endpoint = `http://127.0.0.1:${port}/json/list`
  const page = await until('WebView page', async () => {
    try {
      const pages = await (await fetch(endpoint, { signal: AbortSignal.timeout(1500) })).json()
      const hit = pages.find((p) => /index\.html/.test(p.url))
      if (process.env.REF_DEBUG) console.error('pages:', JSON.stringify(pages.map((p) => p.url)))
      return hit
    } catch {
      return null
    }
  }, 30000)
  console.error('attached page:', page.url)
  const socket = new WebSocket(page.webSocketDebuggerUrl)
  const pending = new Map()
  let nextId = 0
  socket.addEventListener('message', ({ data }) => {
    const msg = JSON.parse(data)
    if (msg.id) { pending.get(msg.id)?.(msg); return }
    if (msg.method === 'Fetch.requestPaused') { handlePaused(call, msg.params); return }
    if (msg.method === 'Runtime.consoleAPICalled') {
      const text = (msg.params.args ?? []).map((a) => a.value ?? a.description ?? '').join(' ')
      console.error(`[page:${msg.params.type}] ${text.slice(0, 300)}`)
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      console.error(`[page:exception] ${JSON.stringify(msg.params.exceptionDetails.exception?.description ?? msg.params).slice(0, 300)}`)
    }
  })
  await new Promise((res, rej) => {
    socket.addEventListener('open', res, { once: true })
    socket.addEventListener('error', rej, { once: true })
  })
  const call = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++nextId
      pending.set(id, (m) =>
        m.error ? reject(new Error(m.error.message)) : resolve(m.result)
      )
      socket.send(JSON.stringify({ id, method, params }))
    })
  const evaluate = async (expression) => {
    const r = await call('Runtime.evaluate', {
      expression,
      returnByValue: true,
      userGesture: true,
      awaitPromise: true,
    })
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? 'eval failed')
    return r.result.value
  }
  await call('Runtime.enable')
  await call('Fetch.enable', { patterns: [{ urlPattern: '*api.deepseek.com*' }] })
  const close = () => {
    socket.close()
    try { adb('forward', '--remove', `tcp:${port}`) } catch {}
  }
  return { evaluate, call, close }
}

async function until(label, check, timeout = 15000) {
  const deadline = Date.now() + timeout
  let last
  do {
    try { last = await check() } catch {}
    if (last) return last
    await delay(150)
  } while (Date.now() < deadline)
  throw new Error(`timeout: ${label}`)
}

// ---- network interception: api.deepseek.com -> mock -------------------------
// Fetch.requestPaused arrives before the request leaves the WebView; we forward
// it to the host mock and fulfill it with the mock's bytes.
function handlePaused(call, params) {
  const { requestId, request } = params
  const u = new URL(request.url)
  let path = u.pathname + u.search
  if (process.env.MOCK_DELAY_MS) path += (path.includes('?') ? '&' : '?') + '__delay=' + process.env.MOCK_DELAY_MS
  const cors = [
    { name: 'access-control-allow-origin', value: '*' },
    { name: 'access-control-allow-methods', value: 'GET,POST,DELETE,OPTIONS' },
    { name: 'access-control-allow-headers', value: 'authorization, x-api-key, anthropic-version, anthropic-beta, content-type, accept' },
  ]
  if (process.env.REF_DEBUG) console.error('paused', request.method, request.url)
  if (request.method === 'OPTIONS') {
    call('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: cors, body: '' })
      .catch((e) => console.error('fulfill failed:', e.message))
    return
  }
  const headers = { ...(request.headers ?? {}) }
  if (process.env.REF_DEBUG) console.error('fwd headers:', JSON.stringify({ k: headers['x-api-key'], a: headers['authorization'] }))
  const req = http.request(
    { host: MOCK.host, port: MOCK.port, path, method: request.method, headers },
    (res) => {
      const chunks = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => {
        const body = Buffer.concat(chunks)
        if (process.env.REF_DEBUG) console.error('intercept', path, '->', res.statusCode, body.length, 'B')
        const responseHeaders = [...cors]
        for (const [k, v] of Object.entries(res.headers)) {
          if (k === 'content-length' || k === 'connection' || k === 'transfer-encoding') continue
          responseHeaders.push({ name: k, value: Array.isArray(v) ? v.join(', ') : String(v) })
        }
        call('Fetch.fulfillRequest', {
          requestId,
          responseCode: res.statusCode,
          responseHeaders,
          body: body.toString('base64'),
        }).catch((e) => console.error('fulfill failed:', e.message))
      })
    }
  )
  req.on('error', (e) => {
    call('Fetch.failRequest', { requestId, errorReason: 'Failed' }).catch(() => {})
  })
  if (request.postData) req.write(request.postData)
  req.end()
}

// ---- ui helpers --------------------------------------------------------------
const setInput = (sel, v) => `(() => {
  const el = [...document.querySelectorAll(${JSON.stringify(sel)})]
    .find(e => e.getBoundingClientRect().width > 0) || document.querySelector(${JSON.stringify(sel)});
  if (!el) return false;
  Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set.call(el, ${JSON.stringify(v)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
})()`
const click = (sel) => `(() => { const el = document.querySelector(${JSON.stringify(sel)}); if (!el) return false; el.click(); return true })()`
const vis = (sel) => `(() => { const el = document.querySelector(${JSON.stringify(sel)}); return !!(el && el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0) })()`
const bodyText = `document.body.innerText`

const KEY = 'sk-mock-test-key'
const passwordInput = 'input[type=password]'

async function enterKey(evaluate) {
  // idempotent: if the key was already accepted the composer textarea is visible
  // and there is no password input — return immediately
  const state = await evaluate(
    `(() => { const p = document.querySelector('input[type=password]');
      const t = document.querySelector('textarea');
      const pv = p && p.getBoundingClientRect().width > 0;
      const tv = t && t.getBoundingClientRect().width > 0;
      return pv ? 'key' : (tv ? 'chat' : 'none') })()`)
  if (state === 'chat') return
  // fast path: write the key through the native bridge (skips flaky verify)
  const bridged = await evaluate(`(window.DeepSeekAndroid && window.DeepSeekAndroid.writeApiKey(${JSON.stringify(KEY)})) || false`)
  if (bridged) {
    // kill and relaunch so the app boots straight into welcome; the CDP
    // session dies with the old WebView, so swap in a fresh one
    adb('shell', 'am', 'force-stop', appId)
    await delay(400)
    if (relaunch) await relaunch()
    else {
      adb('shell', 'monkey', '-p', appId, '-c', 'android.intent.category.LAUNCHER', '1')
      await delay(2500)
    }
    await until('welcome composer', () => evaluate(vis('textarea')), 30000)
    await delay(1500) // WebView DOM reports visible before paint reaches the screen
    return
  }
  await until('password input', () => evaluate(vis(passwordInput)))
  await evaluate(setInput(passwordInput, KEY))
  await evaluate(`(() => {
    const b = [...document.querySelectorAll('button')].find(b => /确定|Confirm|OK/i.test(b.textContent));
    if (b) { b.click(); return true } return false })()`)
  // apikey screen overlays a pre-mounted app shell — a hidden textarea reports
  // a nonzero rect, so also require the password input to be gone or hidden
  await until('key accepted', () => evaluate(
    `(() => { const el = document.querySelector('input[type=password]');
      return !el || el.getBoundingClientRect().width === 0 })()`), 20000)
  await until('welcome composer', () => evaluate(vis('textarea')), 20000)
  const gone = async () => !/输入 API Key|API Key 开始使用/.test(await evaluate(bodyText))
  await until('key screen gone', gone, 12000).catch(async () => {
    await evaluate(`(() => {
      const b = [...document.querySelectorAll('button')].find(b => /确定|Confirm|OK/i.test(b.textContent));
      if (b) { b.click(); return true } return false })()`)
    await until('key screen gone (retry)', gone, 20000)
  })
  await delay(300)
}

// Collect the mock's 'hold' stream bytes (freezes mid-doc) for page replay.
function fetchHoldBytes() {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: MOCK.host, port: MOCK.port, path: '/messages', method: 'POST',
        headers: { 'content-type': 'application/json', 'x-api-key': KEY } },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => resolve(Buffer.concat(chunks)))
        // 'hold' never ends — freeze after 1.5s of silence
        res.socket.once('timeout', () => { req.destroy(); resolve(Buffer.concat(chunks)) })
        res.socket.setTimeout(1500)
      }
    )
    req.on('error', reject)
    req.write(JSON.stringify({ messages: [{ role: 'user', content: 'hold' }] }))
    req.end()
  })
}

// Toggle the mock's global response delay; the delay is chosen when the mock
// receives each request, so a request already pending keeps its delay.
function setMockDelay(ms) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: MOCK.host, port: MOCK.port, path: `/__set_delay?ms=${ms}` },
      (res) => { res.resume(); res.on('end', resolve) })
    req.on('error', reject)
    req.end()
  })
}

const injectStreamReplay = (b64) => `(() => {
  const bytes = Uint8Array.from(atob(${JSON.stringify(b64)}), c => c.charCodeAt(0))
  const CHUNK = 7
  if (!window.__origFetch) window.__origFetch = window.fetch.bind(window)
  window.fetch = (u, opts) => {
    if (!String(u).includes('/messages')) return window.__origFetch(u, opts)
    const stream = new ReadableStream({
      start(c) {
        let i = 0
        const t = setInterval(() => {
          if (i >= bytes.length) return clearInterval(t)
          c.enqueue(bytes.slice(i, i + CHUNK)); i += CHUNK
        }, 10)
      }
    })
    return Promise.resolve(new Response(stream, {
      status: 200, headers: { 'content-type': 'text/event-stream' } }))
  }
  return true })()`

async function sendMessage(evaluate, text) {
  await evaluate(setInput('textarea', text))
  await until('send button', () => evaluate(`(() => {
    const b = document.querySelector('button[aria-label="发送"], button[aria-label="Send"]');
    if (b && b.getAttribute('aria-disabled') !== 'true') { b.click(); return true } return false })()`), 5000)
}

async function replyText(evaluate) {
  return evaluate(`(() => { const t = document.body.innerText; return t })()`)
}

async function openDrawer(evaluate) {
  await enterKey(evaluate)
  await evaluate(`(() => {
    const b = document.querySelector('button[aria-label="Open sidebar"], button[aria-label="打开边栏"]');
    if (b) { b.click(); return true } return false })()`)
  await until('drawer open', () => evaluate(`document.querySelector('.frame')?.getAttribute('data-drawer-open') === 'true'`), 8000).catch(() => {})
  await delay(400)
}

async function chatDone(evaluate, text) {
  await enterKey(evaluate)
  await sendMessage(evaluate, text)
  await until('reply done', async () => /结束/.test(await replyText(evaluate)), 40000)
  await delay(400)
}

// 'refused' scenario: one-line reply so the tested feature fills the frame
async function chatShort(evaluate) {
  await enterKey(evaluate)
  // 每轮开启新对话：避免同一会话累积多条 refused 问答导致截图漂移
  await evaluate(`(() => {
    const b = document.querySelector('button[aria-label="开启新对话"], button[aria-label="New chat"]');
    if (b) { b.click(); return true } return false })()`)
  await delay(600)
  await sendMessage(evaluate, 'refused')
  await until('short reply done', async () => /换个话题/.test(await replyText(evaluate)), 40000)
  await delay(400)
}

async function dispatchTouchOnSession(evaluate, holdMs) {
  // contextmenu is browser-generated on real long-press; dispatch it directly
  // so React's onContextMenu (long-press menu) fires deterministically.
  const ok = await evaluate(`(() => {
    const el = [...document.querySelectorAll('a,li,div')].find(e => e.textContent.trim() === '问候');
    if (!el) return false;
    const r = el.getBoundingClientRect();
    el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
    return true;
  })()`)
  if (!ok) throw new Error('dispatchTouchOnSession: no 问候 row')
  await delay(holdMs)
}

const scenes = {
  async 'apikey.default'(evaluate) {
    await until('apikey form', async () => (await evaluate(vis(passwordInput)))
      && /API Key/.test(await evaluate(bodyText)), 20000)
    await delay(700)
  },
  async 'apikey.invalid'(evaluate) {
    await until('password input', () => evaluate(vis(passwordInput)), 20000)
    await evaluate(setInput(passwordInput, 'sk-wrong-key'))
    await evaluate(`(() => {
      const b = [...document.querySelectorAll('button')].find(b => /确定|Confirm|OK/i.test(b.textContent));
      if (b) { b.click(); return true } return false })()`)
    await until('error text', async () => (await evaluate(bodyText)).match(/API Key|不正确|invalid/i))
    await delay(300)
  },
  async 'apikey.verifying'(evaluate) {
    await until('password input', () => evaluate(vis(passwordInput)), 20000)
    process.env.MOCK_DELAY_MS = '900'
    await evaluate(setInput(passwordInput, KEY))
    await evaluate(`(() => {
      const b = [...document.querySelectorAll('button')].find(b => /确定|Confirm|OK/i.test(b.textContent));
      if (b) { b.click(); return true } return false })()`)
    await delay(350) // capture while the button shows verifying
  },
  async 'welcome.default'(evaluate) {
    await enterKey(evaluate)
    await delay(400)
  },
  async 'welcome.keyboard'(evaluate) {
    await enterKey(evaluate)
    await tapElement(evaluate, 'textarea')
    await until('keyboard', () => evaluate(`document.documentElement.hasAttribute('data-keyboard')`), 8000).catch(() => {})
    await delay(800)
  },
  async 'sider.empty'(evaluate) {
    await openDrawer(evaluate)
  },
  async 'sider.default'(evaluate) { return scenes['sider.sessions'](evaluate) },
  async 'sider.sessions'(evaluate) {
    await chatDone(evaluate, '你好')
    await openDrawer(evaluate)
  },
  async 'debug.send'(evaluate) {
    await enterKey(evaluate)
    await evaluate(setInput('textarea', '你好'))
    await delay(800)
    const st = await evaluate(`(() => {
      const tas = [...document.querySelectorAll('textarea')].map(t => ({v: t.value, vis: t.getBoundingClientRect().width > 0}));
      const bs = [...document.querySelectorAll('button')].map(b => ({l: b.getAttribute('aria-label'), d: b.getAttribute('aria-disabled'), vis: b.getBoundingClientRect().width > 0}));
      return JSON.stringify({tas, bs})
    })()`)
    console.error('SENDSTATE', st)
  },
  async 'sider.search'(evaluate) {
    await openDrawer(evaluate)
    // real tap on the search input so the IME rises — matches native focus
    await tapElement(evaluate, `input[type=search]`)
    await delay(900)
  },
  async 'sider.longpress'(evaluate) {
    await chatDone(evaluate, '你好')
    await openDrawer(evaluate)
    await dispatchTouchOnSession(evaluate, 1500)
    await delay(500)
  },
  async 'sider.delmodal'(evaluate) {
    await chatDone(evaluate, '你好')
    await openDrawer(evaluate)
    await dispatchTouchOnSession(evaluate, 1500)
    await delay(500)
    const ok = await evaluate(`(() => {
      const b=[...document.querySelectorAll('button,div,span,li')].find(e=>/^(删除|Delete)$/.test(e.textContent.trim())&&e.querySelectorAll('*').length<4);
      const t=b&&(b.closest('button,[role=menuitem],[role=menu] *')||b);
      if(t){t.click();return true} return false })()`)
    if (!ok) throw new Error('delete menu item not found')
    await delay(600)
  },
  async 'sider.multiselect'(evaluate) {
    await chatDone(evaluate, '你好')
    await openDrawer(evaluate)
    // selection mode is entered only via the top-right select icon
    // (aria-label = androidSelectChats); the long-press menu has no such item
    const ok = await evaluate(`(() => {
      const b=[...document.querySelectorAll('button[aria-label]')].find(e=>/选择对话|Select/i.test(e.getAttribute('aria-label')));
      if(b){b.click();return true} return false })()`)
    if (!ok) throw new Error('select-chats button not found')
    await delay(600)
  },
  async 'chat.debug'(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, '你好')
    await delay(8000)
    const dump = await evaluate(`(() => {
      const msgs = [...document.querySelectorAll('.ds-assistant-message-main-content')].map(e => e.textContent.slice(0,200));
      const spinners = document.querySelectorAll('[class*=spinner],[class*=loading]').length;
      const body = document.body.innerText.slice(0, 400);
      const store = window.__store_state ? JSON.stringify(window.__store_state).slice(0,500) : 'no-store';
      const vis = document.visibilityState;
      const dpr = window.devicePixelRatio;
      const rr = (e) => { const r = e.getBoundingClientRect(); return {t: Math.round(r.top*dpr), b: Math.round(r.bottom*dpr), l: Math.round(r.left*dpr), r: Math.round(r.right*dpr)} };
      const md = document.querySelector('.ds-markdown');
      const blocks = md ? [...md.children].map(e => ({tag: e.className || e.tagName, ...rr(e)})) : [];
      const composer = document.querySelector('[class*=chat-input-container],[class*=inputContainer],[class*=ChatInput]');
      const bubble = document.querySelector('[class*=user-message-bubble],[class*=UserMessage] [class*=bubble],[class*=userBubble]');
      const asst = [...document.querySelectorAll('[class*=assistant],[class*=Assistant]')].map(e => ({cls: e.className.toString().slice(0,60), ...rr(e)}));
      const bottom = document.querySelector('[class*=bottom],[class*=action-row],[class*=actionRow]');
      const list = document.querySelector('[class*=message-list],[class*=MessageList],[class*=messageList]');
      const caveat = [...document.querySelectorAll('*')].find(e => e.children.length===0 && /仅供参考|AI 生成/.test(e.textContent||''));
      const card = [...document.querySelectorAll('div')].filter(e => /border-radius: 24/.test(getComputedStyle(e).borderRadius)).map(e => ({cls: e.className.toString().slice(0,50), ...rr(e)}));
      const rects = { dpr, blocks, composer: composer && rr(composer), bubble: bubble && rr(bubble), asst, bottom: bottom && rr(bottom), list: list && rr(list), caveat: caveat && rr(caveat), card, innerH: innerHeight*dpr, scrollY: scrollY*dpr };
      const ta = document.querySelector('[class*=textarea],[class*=input] textarea');
      const fr = document.querySelector('[class*=functionRow],[class*=function-row],[class*=toolbar]');
      const pill = document.querySelector('[class*=toggle],[class*=pill],[class*=function] > *');
      const cardEl = [...document.querySelectorAll('div,section')].find(e => { const s = getComputedStyle(e); return s.borderRadius === '24px' && s.borderWidth === '1px'; });
      rects.composerInner = {
        ta: ta && rr(ta), fr: fr && rr(fr), pill: pill && rr(pill),
        card: cardEl && { cls: cardEl.className.toString().slice(0,50), ...rr(cardEl) },
        taStyle: ta && { pad: getComputedStyle(ta).padding, minH: getComputedStyle(ta).minHeight, font: getComputedStyle(ta).font },
      };
      const topEl = document.elementFromPoint(300, 5);
      const topEl2 = document.elementFromPoint(300, 30);
      const hdr = document.querySelector('[class*=header],[class*=Header]');
      rects.top = { el: topEl && topEl.className.toString().slice(0,60), bg: topEl && getComputedStyle(topEl).backgroundColor,
        el2: topEl2 && topEl2.className.toString().slice(0,60), bg2: topEl2 && getComputedStyle(topEl2).backgroundColor,
        hdr: hdr && rr(hdr), hdrBg: hdr && getComputedStyle(hdr).backgroundColor,
        bodyBg: getComputedStyle(document.body).backgroundColor, htmlBg: getComputedStyle(document.documentElement).backgroundColor };
      return JSON.stringify({ msgs, spinners, body: body.slice(0,200), store, vis, rects });
    })()`)
    console.error('DUMP:', dump)
  },
  async 'chat.default'(evaluate) { return chatDone(evaluate, '你好') },
  async 'sider.menu'(evaluate) {
    await openDrawer(evaluate)
    await dispatchTouchOnSession(evaluate, 900)
  },
  async chat_done(evaluate) { return chatDone(evaluate, '你好') },
  async chat_thinking(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, '你好')
    await until('reply done', async () => /结束/.test(await replyText(evaluate)), 30000)
    await delay(400)
  },
  async 'chat.thinking'(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, 'thinking')
    await until('thinking reply done', async () => /结束/.test(await replyText(evaluate)), 40000)
    await delay(400)
  },
  async 'chat.loading'(evaluate) {
    await enterKey(evaluate)
    // Hold the mock response so the "正在思考" indicator persists for the shot.
    // Delay binds when the mock receives the request; reset only after the
    // indicator is up, otherwise the reset can beat the intercepted request.
    await setMockDelay(120000)
    await sendMessage(evaluate, '你好')
    await until('waiting dots', async () =>
      evaluate(`[...document.querySelectorAll('div')].some(d => d.children.length === 3 && [...d.children].every(c => c.tagName === 'SPAN') && d.getBoundingClientRect().height >= 30)`), 10000)
    await setMockDelay(0)
  },
  async 'chat.streaming'(evaluate) {
    // 'hold' freezes the mock stream after the table — deterministic mid-stream.
    // The Fetch proxy can only fulfill whole bodies, so we replay the mock's
    // actual SSE bytes through a fetch() override inside the page instead.
    await enterKey(evaluate)
    const sse = await fetchHoldBytes()
    await evaluate(injectStreamReplay(sse.toString('base64')))
    await sendMessage(evaluate, 'hold')
    await until('frozen mid-stream', async () => /hello mock/.test(await replyText(evaluate)), 30000)
    await delay(1500)
  },
  async 'chat.interrupted'(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, 'interrupted')
    // mock drops the stream mid-way; wait for it to settle: the reply text
    // has arrived AND the waiting-dots row is gone (streaming ended).
    await until('reply text', async () => /假后端|kotlin/.test(await replyText(evaluate)), 30000)
    await until('stream settled', async () =>
      !(await evaluate(`[...document.querySelectorAll('div')].some(d => d.children.length === 3 && [...d.children].every(c => c.tagName === 'SPAN') && d.getBoundingClientRect().height >= 30)`)), 30000)
    await delay(600)
  },
  async 'chat.err_401'(evaluate) { await errorScene(evaluate, '401') },
  async 'chat.err_402'(evaluate) { await errorScene(evaluate, '402') },
  async 'chat.err_429'(evaluate) { await errorScene(evaluate, '429') },
  async 'chat.err_500'(evaluate) { await errorScene(evaluate, '500') },
  async 'chat.edit'(evaluate) {
    await chatShort(evaluate)
    await evaluate(`(() => {
      const b = [...document.querySelectorAll('div')].find(e => /bubble/.test(e.className||'') && e.textContent.trim() === 'refused');
      b && b.click(); })()`)
    await until('editor', async () => evaluate(`!!document.querySelector('textarea[aria-label]')`), 10000)
    await delay(500)
  },
  async 'chat.menu'(evaluate) {
    await chatShort(evaluate)
    await evaluate(`(() => {
      const b = [...document.querySelectorAll('div')].find(e => /bubble/.test(e.className||'') && e.textContent.trim() === 'refused');
      b && b.dispatchEvent(new MouseEvent('contextmenu', {bubbles: true})); })()`)
    await until('menu', async () => evaluate(`!!document.querySelector('[role=menu]')`), 10000)
    await delay(500)
  },
  async 'chat.branch'(evaluate) {
    await chatShort(evaluate)
    // regenerate -> second branch -> BranchSwitcher "2 / 2"
    await evaluate(`(() => {
      const b = document.querySelector('[aria-label*="重新生成"],[aria-label*="Regenerate"]');
      b && b.click(); })()`)
    await until('switcher', async () => evaluate(`!!document.querySelector('[aria-label*="版本"],[aria-label*="ersion"]')`), 60000)
    await delay(500)
  },
  async 'chat.continue'(evaluate) {
    await enterKey(evaluate)
    const sse = await fetchHoldBytes()
    await evaluate(injectStreamReplay(sse.toString('base64')))
    await sendMessage(evaluate, 'hold')
    await until('frozen mid-stream', async () => /hello mock/.test(await replyText(evaluate)), 30000)
    await delay(800)
    await evaluate(`(() => {
      const b = document.querySelector('[aria-label="停止生成"],[aria-label="Stop"]');
      b && b.click(); })()`)
    await until('continue button', async () => evaluate(
      `[...document.querySelectorAll('button')].some(b => /继续生成|Continue/.test(b.textContent||''))`), 15000)
    await delay(500)
  },
  async 'chat.toast'(evaluate) {
    await chatShort(evaluate)
    // mock TTS otherwise returns valid PCM — force a failure so the toast shows
    await fetch('http://127.0.0.1:8321/__set_tts_fail?code=500')
    await evaluate(`(() => {
      const btns = [...document.querySelectorAll('[aria-label="朗读"],[aria-label="Read aloud"]')];
      const b = btns[btns.length - 1];
      b && b.click(); })()`)
    await until('toast', async () => evaluate(
      `[...document.querySelectorAll('div')].some(e => /朗读失败|Failed to read/.test(e.textContent||''))`), 15000)
    await fetch('http://127.0.0.1:8321/__set_tts_fail?code=0')
    await delay(300)
  },
  async 'chat.md'(evaluate) {
    await enterKey(evaluate); await sendMessage(evaluate, 'md')
    await until('md done', async () => /项二/.test(await replyText(evaluate)), 40000)
    await delay(400)
  },
  async 'chat.code'(evaluate) {
    await enterKey(evaluate); await sendMessage(evaluate, 'code')
    await until('code done', async () => /println/.test(await replyText(evaluate)), 40000)
    await delay(500)
  },
  async 'chat.math'(evaluate) {
    await enterKey(evaluate); await sendMessage(evaluate, 'math')
    await until('math done', async () => /行内公式/.test(await replyText(evaluate)), 40000)
    await delay(700)
  },
  async 'chat.table'(evaluate) {
    await enterKey(evaluate); await sendMessage(evaluate, 'table')
    await until('table done', async () => /alpha/.test(await replyText(evaluate)), 40000)
    await delay(400)
  },
  async 'chat.mermaid'(evaluate) {
    await enterKey(evaluate); await sendMessage(evaluate, 'mermaid')
    await until('mermaid svg', async () => evaluate(
      `(() => { const s = document.querySelector('.md-mermaid-diagram svg');
         return !!(s && s.getBoundingClientRect().width > 40) })()`), 40000)
    await delay(2500)
  },
  async 'chat.long'(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, 'long')
    // wait for stream end — the mock's message_delta sets the header title 问候
    await until('stream done (title)', async () => /问候/.test(await evaluate(bodyText)), 60000)
    await delay(800)
  },
  async 'searchpanel.default'(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, 'search')
    // .summary chip = role=button containing "N 个网页"; tap opens the bottom-sheet
    await until('sources chip', async () => evaluate(`(() => {
      const el = [...document.querySelectorAll('[role=button]')].find(e => /个网页|pages?$/i.test((e.textContent||'').trim()));
      if (!el) return false; el.click(); return true
    })()`), 40000)
    await until('panel open', async () =>
      evaluate(`(() => { const d = document.querySelector('section[role=dialog][aria-modal="true"]'); return !!(d && d.getBoundingClientRect().height > 0) })()`), 10000)
    await delay(800)
  },
  async 'attachments.default'(evaluate) {
    await enterKey(evaluate)
    // pm clear revoked it — re-grant so RecentPhotos.list() returns images
    adb('shell', 'pm', 'grant', appId, 'android.permission.READ_MEDIA_IMAGES')
    // freeze CSS animation — live rAF kills QEMU's GL path
    await evaluate(`document.head.insertAdjacentHTML('beforeend','<style>*{animation-play-state:paused !important;transition:none !important}</style>')`)
    const ok = await evaluate(`(() => {
      const b = document.querySelector('[aria-label="uploadFile"],[aria-label="上传文件"]');
      if (!b) return false; b.click(); return true })()`)
    if (!ok) throw new Error('uploadFile toggle not found')
    await until('recent photos row', () => evaluate(
      `!!document.querySelector('.recent button img, [class*=recent] img')`), 15000)
    await delay(600)
    // select the two most recent photos
    await evaluate(`(() => {
      const bts = [...document.querySelectorAll('[class*=recent] button')];
      bts[0] && bts[0].click(); })()`)
    await delay(500)
    await evaluate(`(() => {
      const bts = [...document.querySelectorAll('[class*=recent] button')];
      bts[1] && bts[1].click(); })()`)
    await until('draft chips', () => evaluate(
      `document.querySelectorAll('[class*=attachment]').length > 0`), 10000)
    await delay(700)
  },
  // file attached through the hidden input — deterministic via CDP DOM.setFileInputFiles
  async 'chat.files'(evaluate, call) {
    await enterKey(evaluate)
    // inject an in-memory File through the app's own hidden input: a real
    // /sdcard path would hit scoped-storage (blob write/read fails) and the
    // SAF picker cannot be driven deterministically
    await evaluate(`(() => {
      const input = document.querySelector('input[type=file]');
      const dt = new DataTransfer();
      dt.items.add(new File(['parity test document\\n'], 'parity_note.txt', { type: 'text/plain' }));
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      return true })()`)
    await until('draft chip', () => evaluate(
      `document.querySelectorAll('[class*=attachment]').length > 0`), 10000)
    await delay(600)
    await sendMessage(evaluate, 'refused')
    await until('reply done', async () => /换个话题/.test(await replyText(evaluate)), 40000)
    await delay(600)
  },
  async 'attachments.measure'(evaluate) {
    await scenes['attachments.default'](evaluate)
    const m = await evaluate(`(() => {
      const dpr = devicePixelRatio;
      const rr = (e) => { if(!e) return null; const r = e.getBoundingClientRect();
        return {t:+((r.top*dpr).toFixed(1)), b:+((r.bottom*dpr).toFixed(1)),
                l:+((r.left*dpr).toFixed(1)), r:+((r.right*dpr).toFixed(1))} };
      const q = (s) => document.querySelector(s);
      return JSON.stringify({
        dpr,
        wrapper: rr(q('[class*=wrapper]')),
        attachments: rr(q('[class*=attachments]')),
        chip0: rr(q('[class*=attachments] [class*=attachment]')),
        chip1: rr([...document.querySelectorAll('[class*=attachments] [class*=attachment]')][1]),
        sugg: rr(q('[class*=quickSuggestionsList],[class*=suggestion]')),
        textarea: rr(q('textarea')),
        functionRow: rr(q('[class*=functionRow]')),
        recent: rr(q('[class*=recent]')),
        recentBtn0: rr(q('[class*=recent] button')),
        actions: rr(q('[class*=actions]')),
        actionBtn0: rr(q('[class*=actions] button')),
      }, null, 1)
    })()`)
    console.error('MEASURE ' + m)
  },
  async 'imageviewer.default'(evaluate) {
    await enterKey(evaluate)
    adb('shell', 'pm', 'grant', appId, 'android.permission.READ_MEDIA_IMAGES')
    await evaluate(`document.head.insertAdjacentHTML('beforeend','<style>*{animation-play-state:paused !important;transition:none !important}</style>')`)
    const ok = await evaluate(`(() => {
      const b = document.querySelector('[aria-label="uploadFile"],[aria-label="上传文件"]');
      if (!b) return false; b.click(); return true })()`)
    if (!ok) throw new Error('uploadFile toggle not found')
    await until('recent photos row', () => evaluate(
      `!!document.querySelector('[class*=recent] button img, [class*=recent] img')`), 15000)
    await delay(500)
    await evaluate(`(() => {
      const bts = [...document.querySelectorAll('[class*=recent] button')];
      bts[0] && bts[0].click(); })()`)
    await until('draft chips', () => evaluate(
      `document.querySelectorAll('[class*=attachment]').length > 0`), 10000)
    await delay(600)
    // click the chip image itself (not the remove badge) to open the viewer
    await evaluate(`(() => {
      const img = document.querySelector('[class*=attachment] img');
      if (!img) return false; (img.closest('[role=button],button,div')||img).click(); return true })()`)
    await until('viewer open', () => evaluate(`(() => {
      const d = [...document.querySelectorAll('div')].find(e =>
        /裁剪|Crop/.test(e.textContent||'') && e.querySelector('img'));
      return !!d })()`), 10000)
    // System immersive cling (package "android") overlays the viewer on first
    // open; it's not in the DOM, so dismiss via uiautomator bounds + adb tap.
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/cling.xml')
    const cling = adb('shell', 'cat', '/data/local/tmp/cling.xml').toString()
    const clingOk = /immersive_cling_title/.test(cling)
      && /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(
        /android:id\/ok[^>]*bounds="[^"]+"/.exec(cling)?.[0] || '')
    if (clingOk) {
      adb('shell', 'input', 'tap',
        String((+clingOk[1] + +clingOk[3]) / 2), String((+clingOk[2] + +clingOk[4]) / 2))
    }
    await delay(700)
  },
  async 'voice.default'(evaluate, call) {
    await enterKey(evaluate)
    // The bridge object is a native proxy: its methods can't be stubbed and
    // module code captures it at load. Inject a Proxy that replaces
    // window.DeepSeekAndroid before every document loads, then reload so the
    // app recaptures the proxy. Also stubs pointer capture (synthetic events
    // have no active pointer) — guest audio capture hard-kills QEMU here, so
    // requestRecording resolves 'ready' without touching the audio HAL.
    await call('Page.enable')
    await call('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
      // window.DeepSeekAndroid is injected by Chromium possibly AFTER this
      // script runs, so trap both orders: accessor for future assignment and
      // a poll for a late own-property definition.
      const handler = {
        get(t, k) {
          if (k === 'requestRecording') return () =>
            setTimeout(() => window.dispatchEvent(new CustomEvent('android-recording', { detail: 'ready' })), 30)
          if (k === 'recordingLevel') return () => 0
          if (k === 'finishRecording') return () => true
          if (k === '__voiceStub') return true
          const v = t[k]
          return typeof v === 'function' ? v.bind(t) : v
        },
      }
      const wrap = (o) => (o && o.__voiceStub) ? o : new Proxy(o, handler)
      Element.prototype.setPointerCapture = function(){}
      Element.prototype.releasePointerCapture = function(){}
      Element.prototype.hasPointerCapture = () => false
      let cur = window.DeepSeekAndroid
      try {
        Object.defineProperty(window, 'DeepSeekAndroid', {
          configurable: true, enumerable: true,
          get() { return cur ? wrap(cur) : cur },
          set(v) { cur = v },
        })
      } catch (e) {}
      const iv = setInterval(() => {
        const o = window.DeepSeekAndroid
        if (o && !o.__voiceStub) window.DeepSeekAndroid = wrap(o)
      }, 5)
      setTimeout(() => clearInterval(iv), 60000)
    })()` })
    await call('Page.reload', { ignoreCache: true })
    await delay(2500)
    // re-attach state: page reloaded, wait until interactive again
    await until('page ready', () => evaluate(`document.readyState === 'complete'`), 20000)
    // freeze animation+transition for a deterministic frame; also the live
    // CSS animation on this overlay crashes QEMU's GL path (host SIGSEGV)
    await evaluate(`document.head.insertAdjacentHTML('beforeend','<style>*{animation-play-state:paused !important;transition:none !important}</style>')`)
    const ok = await evaluate(`(() => {
      const b = document.querySelector('[aria-label="语音输入"],[aria-label="Voice input"]');
      if (!b) return false; b.click(); return true })()`)
    if (!ok) throw new Error('voice toggle not found')
    await until('hold button', () => evaluate(`(() => {
      const b = [...document.querySelectorAll('button')].find(e => /按住说话|Hold to talk/.test(e.textContent));
      if (!b) return false; window.__hold = b; return true })()`), 10000)
    const down = await evaluate(`(() => {
      const el = window.__hold; const r = el.getBoundingClientRect();
      el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, isPrimary: true,
        button: 0, buttons: 1, pointerId: 7, pointerType: 'touch',
        clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
      return true })()`)
    await until('recording overlay', () => evaluate(`(() => {
      const o = [...document.querySelectorAll('div')].find(d => d.getAttribute('data-recording') === 'true');
      return !!o })()`), 15000)
    await delay(600)
  },
  async 'settings.root'(evaluate) {
    await openDrawer(evaluate)
    await evaluate(`(() => { const b=document.querySelector('[aria-label="设置"],[aria-label="Settings"]') || [...document.querySelectorAll('button,a,li')].find(e=>/设置|API Key/.test(e.textContent.trim())); if(b){b.click();return true} return false })()`)
    await delay(600)
  },
  async 'settings.account'(evaluate) { await settingsPage(evaluate, /账号管理|Account/) },
  async 'settings.data'(evaluate) { await settingsPage(evaluate, /数据管理|Data/) },
  async 'settings.export'(evaluate) {
    await settingsPage(evaluate, /数据管理|Data/)
    await settingsRow(evaluate, /导出所有历史对话|Export all/)
  },
  async 'settings.font'(evaluate) { await settingsPage(evaluate, /字体大小|Text size/) },
  async 'settings.personalization'(evaluate) { await settingsPage(evaluate, /个性化|Personalization/) },
  async 'settings.agreements'(evaluate) { await settingsPage(evaluate, /服务协议|agreement|协议/) },
  async 'debug.meas'(evaluate) {
    await enterKey(evaluate)
    const meas = await evaluate(`(() => {
      const pick = (sel) => { const el = document.querySelector(sel); if (!el) return null;
        const r = el.getBoundingClientRect(); return {x: r.x, y: r.y, w: r.width, h: r.height} }
      return JSON.stringify({
        dpr: devicePixelRatio, vw: innerWidth, vh: innerHeight,
        header: pick('.header') || pick('[class*=header]'),
        greeting: pick('[class*=greeting]'),
        composer: pick('[class*=composer]'),
        wrapper: pick('[class*=wrapper]'),
        functionRow: pick('[class*=functionRow]'),
        textarea: pick('textarea'),
        toggles: [...document.querySelectorAll('[class*=toggle]')].map(e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height}}),
      }) })()`)
    console.log('MEAS', meas)
  },
}

// open settings.root, then click the Row whose label matches `re`, and wait
// until the page region's aria-label changes (sub-page mounted)
async function settingsRow(evaluate, re) {
  const src = re.source.replace(/\\/g, '\\\\')
  const ok = await evaluate(`(() => {
    const els = [...document.querySelectorAll('button,[role=button],div,li')].filter(e => e.children.length && new RegExp('${src}').test(e.textContent.trim().slice(0,60)) && e.getBoundingClientRect().height < 90);
    const b = els[els.length - 1]; if (b) { b.click(); return true } return false })()`)
  if (!ok) throw new Error(`settings row ${re} not found`)
  await delay(600)
}

async function settingsPage(evaluate, re) {
  await scenes['settings.root'](evaluate)
  await settingsRow(evaluate, re)
}

async function errorScene(evaluate, code) {
  await enterKey(evaluate)
  await sendMessage(evaluate, code)
  await until('error visible', async () => (await replyText(evaluate)).match(/错误|失败|error|key|余额|频繁|内部/i), 20000)
  await delay(400)
}

let relaunch = null

const main = async () => {
  let session = await launch()
  const evaluate = (expr) => session.evaluate(expr)
  relaunch = async () => {
    try { session.close() } catch {}
    session = await launch()
  }
  try {
    const fn = scenes[scene] ?? scenes[`${scene}.default`]
    if (!fn) throw new Error(`unknown scene ${scene}`)
    await fn(evaluate, (...a) => session.call(...a))
    if (process.env.PRESS_SEL) {
      const r = await evaluate(`(() => { const el = ${process.env.PRESS_SEL}; if (!el) return null; const b = el.getBoundingClientRect(); return {x:b.left+b.width/2, y:b.top+b.height/2} })()`)
      if (r) {
        const o = webViewOrigin()
        const dpr = await evaluate('devicePixelRatio')
        console.log('PRESSXY', Math.round(r.x * dpr + o.x), Math.round(r.y * dpr + o.y))
      } else console.log('PRESSXY none')
    }
    if (process.env.PRESS_RECT) {
      const r = await evaluate(`(() => { const el = ${process.env.PRESS_RECT}; if (!el) return null; const b = el.getBoundingClientRect(); return {l:b.left,t:b.top,r:b.right,b:b.bottom} })()`)
      if (r) {
        const o = webViewOrigin()
        const dpr = await evaluate('devicePixelRatio')
        console.log('PRESSRECT', Math.round(r.l*dpr+o.x), Math.round(r.t*dpr+o.y), Math.round(r.r*dpr+o.x), Math.round(r.b*dpr+o.y))
      } else console.log('PRESSRECT none')
    }
    await delay(600)
    screencap(out)
    console.log(`ref scene ${scene} -> ${out}`)
  } catch (e) {
    console.error(`ref scene ${scene} FAILED: ${e.message}`)
    try { screencap(out.replace('.png', '.fail.png')) } catch {}
    try { console.error('bodytext:', (await evaluate(bodyText)).slice(0, 500)) } catch {}
    process.exitCode = 1
  } finally {
    try { session.close() } catch {}
    process.exit(process.exitCode ?? 0)
  }
}

main().catch((e) => {
  console.error('refdrive failed:', e.message)
  process.exit(1)
})
