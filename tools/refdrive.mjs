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
  return { evaluate, close }
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

async function dispatchTouchOnSession(evaluate, holdMs) {
  // synthesized touchstart/end on the first session row
  await evaluate(`(() => {
    const el = [...document.querySelectorAll('a,li,div')].find(e => e.textContent.trim() === '问候');
    if (!el) return false;
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const t = new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
    el.dispatchEvent(new TouchEvent('touchstart', { touches: [t], targetTouches: [t], bubbles: true }));
    return true;
  })()`)
  await delay(holdMs)
  await evaluate(`(() => {
    const el = [...document.querySelectorAll('a,li,div')].find(e => e.textContent.trim() === '问候');
    if (!el) return false;
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const t = new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
    el.dispatchEvent(new TouchEvent('touchend', { changedTouches: [t], bubbles: true }));
    return true;
  })()`)
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
    await evaluate(`(() => { const s = document.querySelector('[data-drawer-open] input, aside input'); if (s) { s.focus(); return true } return false })()`)
    await delay(600)
  },
  async 'sider.longpress'(evaluate) {
    await chatDone(evaluate, '你好')
    await openDrawer(evaluate)
    await dispatchTouchOnSession(evaluate, 1500)
    await delay(500)
  },
  async 'sider.multiselect'(evaluate) {
    await chatDone(evaluate, '你好')
    await openDrawer(evaluate)
    await dispatchTouchOnSession(evaluate, 1500)
    await delay(500)
    await evaluate(`(() => { const b=[...document.querySelectorAll('[role=menuitem],button,li')].find(e=>/多选|select/i.test(e.textContent)); if(b){b.click();return true} return false })()`)
    await delay(500)
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
  async 'chat.streaming'(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, 'slow')
    await until('mid-stream text', async () => /列表项一|引用段落/.test(await replyText(evaluate)), 30000)
  },
  async 'chat.interrupted'(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, 'interrupted')
    await until('interrupted notice', async () => (await replyText(evaluate)).match(/中断|interrupted|重新生成|停止/))
    await delay(400)
  },
  async 'chat.err_401'(evaluate) { await errorScene(evaluate, '401') },
  async 'chat.err_402'(evaluate) { await errorScene(evaluate, '402') },
  async 'chat.err_429'(evaluate) { await errorScene(evaluate, '429') },
  async 'chat.err_500'(evaluate) { await errorScene(evaluate, '500') },
  async 'chat.long'(evaluate) {
    await enterKey(evaluate)
    await sendMessage(evaluate, 'long')
    await until('long reply', async () => (await replyText(evaluate)).length > 2500, 40000)
    await delay(500)
  },
  async 'settings.root'(evaluate) {
    await openDrawer(evaluate)
    await evaluate(`(() => { const b=document.querySelector('[aria-label="设置"],[aria-label="Settings"]') || [...document.querySelectorAll('button,a,li')].find(e=>/设置|API Key/.test(e.textContent.trim())); if(b){b.click();return true} return false })()`)
    await delay(600)
  },
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
    await fn(evaluate)
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
