#!/usr/bin/env node
// tools/refscroll.mjs <serial> <pkg> <list:chat|sider>
// Inertia-scroll fling equivalence on the ref app: for each dir x velocity
// cell, clamps the list to the far bound with three heavy opposite flings,
// reads scrollTop, fires the test fling, reads again. JSON per cell.
import { execFileSync } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'

const [serial, appId, list] = process.argv.slice(2)
const adb = (...args) =>
  execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8', timeout: 20000 }).trim()
const SCALE = Number(process.env.GRID_SCALE || '0.36363636') // css px per device px

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

const sel = list === 'sider' ? '[class*=listWrap] > [class*=list]' : '[class*=scroll]'
const top = () => evaluate(`(document.querySelector('${sel}')||{scrollTop:null}).scrollTop`)
const settle = async () => {
  let last = await top()
  for (let i = 0; i < 24; i++) {
    await delay(250)
    const t = await top()
    if (t === last || t === null) return last
    last = t
  }
  return last
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

// swipe geometry in css px: x inside the list, y range mid-screen.
// chat list spans full width; sider list lives in the drawer (x < ~324 css px).
const X = list === 'sider' ? 120 : 196
const Y_HI = 655, Y_LO = 437      // css px equivalents of 1800 / 1200 dev px
const DIST = Math.round(400 * SCALE)  // 400 dev px -> css px

const out = { list, cells: {} }
for (const dir of ['up', 'down']) {
  for (const v of [500, 1500, 3000, 6000]) {
    const dur = 400000 / v
    // clamp to the far bound: three heavy flings in the opposite direction
    for (let i = 0; i < 3; i++) {
      if (dir === 'up') await swipe(X, Y_LO, X, Y_LO + DIST, 66)
      else await swipe(X, Y_HI, X, Y_HI - DIST, 66)
      await settle()
    }
    const before = await top()
    const [y1, y2] = dir === 'up' ? [Y_HI, Y_HI - DIST] : [Y_LO, Y_LO + DIST]
    await swipe(X, y1, X, y2, dur)
    const after = await settle()
    out.cells[`${dir}_${v}`] = { before, after, delta: after === null || before === null ? null : after - before }
  }
}
console.log(JSON.stringify(out))
process.exit(0)
