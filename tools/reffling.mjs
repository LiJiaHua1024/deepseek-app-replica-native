#!/usr/bin/env node
// tools/reffling.mjs <serial> <pkg> — measure the ref drawer's follow curve
// and fling behavior via CDP. Installs a pointermove sampler that records
// (t, clientX, --drawer-progress); emits JSON on stdout:
//   { follow: [[t,x,progress]...], flings: {v: {release, open}} }
// GRID_SCALE css-px per device-px (0.3636 = 1/2.75).
import { execFileSync } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'

const [serial, appId] = process.argv.slice(2)
const adb = (...args) =>
  execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8', timeout: 20000 }).trim()
const SCALE = Number(process.env.GRID_SCALE || '0.36363636')

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

await evaluate(`window.__samples = [];
window.__frame = document.querySelector('[data-drawer-open]');
const prog = () => {
  const f = document.querySelector('[data-drawer-open]');
  return f ? parseFloat(getComputedStyle(f).getPropertyValue('--drawer-progress')) : NaN;
};
document.addEventListener('pointermove', (e) => {
  window.__samples.push([e.timeStamp, e.clientX, prog()]);
}, true);
'sampler installed'`)

const isOpen = () => evaluate("!!document.querySelector('[class*=drawerMask]')")
const closeIfOpen = async () => {
  for (let i = 0; i < 6; i++) {
    if (!(await isOpen())) return
    await evaluate("document.querySelector('[class*=drawerMask]')?.click()")
    await delay(600)
  }
  for (let i = 0; i < 3; i++) {
    if (!(await isOpen())) return
    await swipe(345, 436, 100, 436, 200)
    await delay(600)
  }
}
const progNow = () => evaluate(`(() => {
  const f = document.querySelector('[data-drawer-open]');
  return f ? parseFloat(getComputedStyle(f).getPropertyValue('--drawer-progress')) : null;
})()`)

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

const out = { follow: null, flings: {} }
await closeIfOpen()
// follow drag 29 -> 29+236 css px over 2000ms (matches native 80->730 dev px)
await evaluate('window.__samples = []')
await swipe(29, 436, 29 + 650 * SCALE, 436, 2000, 40)
out.follow = await evaluate('window.__samples')
out.followRelease = await progNow()
await delay(600)
await closeIfOpen()

for (const v of [500, 1500, 3000, 6000]) {
  const dxDev = 400
  const dur = 400000 / v
  await evaluate('window.__samples = []')
  await swipe(29, 436, 29 + dxDev * SCALE, 436, dur, 20)
  const release = await progNow()
  await delay(800)
  const open = await isOpen()
  out.flings[v] = { release, open, samples: await evaluate('window.__samples') }
  await closeIfOpen()
}
console.log(JSON.stringify(out))
process.exit(0)
