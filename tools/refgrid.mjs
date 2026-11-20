#!/usr/bin/env node
// tools/refgrid.mjs <serial> <pkg> — drive the ref app through the drawer
// gesture grid via CDP Input.dispatchTouchEvent (precise timestamps, reliable
// delivery), reading ground-truth open state from the .drawerMask element.
// Reads combos JSON from stdin: [{dx,dur,dy}] -> prints JSON results to stdout.
import { execFileSync } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'

const [serial, appId] = process.argv.slice(2)
const adb = (...args) =>
  execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8', timeout: 20000 }).trim()

const combos = JSON.parse(await new Promise((res) => {
  let s = ''; process.stdin.on('data', (d) => s += d); process.stdin.on('end', () => res(s))
}))

const pid = adb('shell', `pidof ${appId} || true`)
const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`)
const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
const page = pages.find((p) => /index\.html/.test(p.url))
const socket = new WebSocket(page.webSocketDebuggerUrl)
const pending = new Map(); let nextId = 0
socket.addEventListener('message', ({ data }) => {
  const m = JSON.parse(data); if (m.id) pending.get(m.id)?.(m)
})
await new Promise((res, rej) => { socket.addEventListener('open', res, { once: true }); socket.addEventListener('error', rej, { once: true }) })
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId; pending.set(id, (m) => m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result))
  socket.send(JSON.stringify({ id, method, params }))
})
const evaluate = async (expr) => (await call('Runtime.evaluate', { expression: expr, returnByValue: true })).result?.value

const isOpen = () => evaluate("!!document.querySelector('[class*=drawerMask]')")
const closeIfOpen = async () => {
  for (let i = 0; i < 6; i++) {
    if (!(await isOpen())) return
    await evaluate("document.querySelector('[class*=drawerMask]')?.click()")
    await delay(600)
  }
  // mask click can drop during transition — fall back to a strong left swipe
  for (let i = 0; i < 3; i++) {
    if (!(await isOpen())) return
    await swipe(345, 436, 100, 436, 200)
    await delay(600)
  }
}

async function swipe(x1, y1, x2, y2, durMs, steps = 20) {
  const t0 = Date.now() / 1000
  await call('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x1, y: y1, id: 1 }], timestamp: t0 })
  for (let i = 1; i < steps; i++) {
    const f = i / steps
    await call('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x1 + (x2 - x1) * f, y: y1 + (y2 - y1) * f, id: 1 }], timestamp: t0 + durMs * f / 1000 })
    await delay(durMs / steps)
  }
  await call('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [], timestamp: t0 + durMs / 1000 })
}

const out = []
const SCALE = Number(process.env.GRID_SCALE || '1') // css px per combo unit
for (const c of combos) {
  await closeIfOpen()
  const pre = (await isOpen()) ? 'open' : 'closed'
  const dx = c.dx * SCALE, dy = c.dy * SCALE
  await swipe(29, 436, 29 + dx, 436 + dy, c.dur)
  await delay(700)
  const open = (await isOpen()) ? 'open' : 'closed'
  let close = 'n-a'
  if (open === 'open') {
    await swipe(345, 436, 345 - dx, 436 + dy, c.dur)
    await delay(700)
    close = (await isOpen()) ? 'open' : 'closed'
    await closeIfOpen()
  }
  out.push({ dx: c.dx, dur: c.dur, dy: c.dy, open, close, pre })
  console.error('combo', c.dx, c.dur, c.dy, '->', open, close, '(pre:' + pre + ')')
}
console.log(JSON.stringify({ combos: out }))
process.exit(0)
