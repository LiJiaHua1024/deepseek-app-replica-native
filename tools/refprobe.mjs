#!/usr/bin/env node
// Probe: dispatch a swipe via CDP and record the (clientX,timeStamp) the page sees.
import { execFileSync } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
const [serial, appId] = process.argv.slice(2)
const adb = (...a) => execFileSync('adb', ['-s', serial, ...a], { encoding: 'utf8', timeout: 20000 }).trim()
const pid = adb('shell', `pidof ${appId} || true`)
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`)
const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
const page = pages.find((p) => /index\.html/.test(p.url))
const socket = new WebSocket(page.webSocketDebuggerUrl)
const pending = new Map(); let nextId = 0
socket.addEventListener('message', ({ data }) => { const m = JSON.parse(data); if (m.id) pending.get(m.id)?.(m) })
await new Promise((r, j) => { socket.addEventListener('open', r, { once: true }); socket.addEventListener('error', j, { once: true }) })
const call = (m, p = {}) => new Promise((res, rej) => { const id = ++nextId; pending.set(id, (x) => x.error ? rej(new Error(JSON.stringify(x.error))) : res(x.result)); socket.send(JSON.stringify({ id, method: m, params: p })) })
const evaluate = async (e) => (await call('Runtime.evaluate', { expression: e, returnByValue: true })).result?.value

await evaluate(`window.__s=[]; document.addEventListener('pointermove', e => { for (const c of (e.getCoalescedEvents?.()||[e])) __s.push([c.clientX, c.timeStamp]) }, true); window.__up=null; document.addEventListener('pointerup', e => __up=e.timeStamp, true); 'ok'`)

const [x1, x2, dur] = [29, 29 + 36.36, 200] // dx=100 dev px -> 36.36 css
const steps = 20
const t0 = Date.now() / 1000
await call('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x1, y: 436, id: 1 }], timestamp: t0 })
for (let i = 1; i < steps; i++) {
  const f = i / steps
  await call('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x1 + (x2 - x1) * f, y: 436, id: 1 }], timestamp: t0 + dur * f / 1000 })
  await delay(dur / steps)
}
await call('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [], timestamp: t0 + dur / 1000 })
await delay(500)
const s = await evaluate('JSON.stringify({s:__s.slice(-30), up:__up})')
console.log(s)
process.exit(0)
