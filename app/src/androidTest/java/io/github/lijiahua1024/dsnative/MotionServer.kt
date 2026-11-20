package io.github.lijiahua1024.dsnative

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Precision MotionEvent injector: an instrumentation batch runner that
// executes every line of inject.cmd in the app's external-files dir
// (/sdcard/Android/data/<pkg>/files — shell-writable, app-readable). Each
// finished batch appends real injection timestamps to inject.done; every line
// also lands in inject.log immediately so the host can time screenshots.
//
// commands:
//   tap x y
//   hold x y hold_ms
//   down x y
//   move x y
//   up x y
//   seq x1 y1 x2 y2 steps duration_ms          — evenly-spaced precise eventTime
//   flingseq x1 y1 x2 y2 steps duration_ms     — same, but UP lands <56ms after
//                                              the last MOVE (ref fling window)
//   sleep ms
//
// Run once for a batch:
//   adb shell am instrument -w -e class \
//     io.github.lijiahua1024.dsnative.MotionServer#serve \
//     io.github.lijiahua1024.dsnative.test/androidx.test.runner.AndroidJUnitRunner
// (reads inject.cmd, executes every line, then exits — inject.done holds the log)
@RunWith(AndroidJUnit4::class)
class MotionServer {
    @Test
    fun serve() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val auto = inst.uiAutomation
        val dir = inst.targetContext.getExternalFilesDir(null)
            ?: throw IllegalStateException("no external files dir")
        val cmd = File(dir, "inject.cmd")
        val done = File(dir, "inject.done")
        val live = File(dir, "inject.log")
        val log = StringBuilder()
        dir.mkdirs()
        fun liveLog(s: String) { live.appendText(s); log.append(s) }
        if (!cmd.exists()) throw IllegalStateException("no inject.cmd in $dir")
        val lines = cmd.readText().lines().filter { it.isNotBlank() }
        cmd.delete()
        var downAt = 0L
        for (line in lines) {
            val p = line.trim().split(" ")
            when (p[0]) {
                "tap" -> {
                    val x = p[1].toFloat(); val y = p[2].toFloat()
                    downAt = SystemClock.uptimeMillis()
                    inject(auto, MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0))
                    Thread.sleep(60)
                    inject(auto, MotionEvent.obtain(downAt, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP, x, y, 0))
                    liveLog("tap $x $y\n")
                }
                "hold" -> {
                    val x = p[1].toFloat(); val y = p[2].toFloat(); val ms = p[3].toLong()
                    downAt = SystemClock.uptimeMillis()
                    inject(auto, MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0))
                    liveLog("held $x $y\n")
                    Thread.sleep(ms)
                    inject(auto, MotionEvent.obtain(downAt, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP, x, y, 0))
                    liveLog("hold-up $x $y $ms\n")
                }
                "down" -> {
                    downAt = SystemClock.uptimeMillis()
                    inject(auto, MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN,
                        p[1].toFloat(), p[2].toFloat(), 0))
                    liveLog("down $downAt\n")
                }
                "move" -> {
                    inject(auto, MotionEvent.obtain(downAt, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE, p[1].toFloat(), p[2].toFloat(), 0))
                    liveLog("move ${p[1]} ${p[2]}\n")
                }
                "up" -> {
                    inject(auto, MotionEvent.obtain(downAt, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP, p[1].toFloat(), p[2].toFloat(), 0))
                    liveLog("up ${SystemClock.uptimeMillis()}\n")
                }
                "seq", "flingseq" -> {
                    val x1 = p[1].toFloat(); val y1 = p[2].toFloat()
                    val x2 = p[3].toFloat(); val y2 = p[4].toFloat()
                    val steps = p[5].toInt(); val durMs = p[6].toLong()
                    val fling = p[0] == "flingseq"
                    downAt = SystemClock.uptimeMillis()
                    val wall = downAt
                    inject(auto, MotionEvent.obtain(downAt, wall, MotionEvent.ACTION_DOWN, x1, y1, 0))
                    liveLog("seq-start $wall\n")
                    val moveCount = if (fling) steps else steps - 1
                    for (i in 1..moveCount) {
                        val target = wall + durMs * i / steps
                        val x = x1 + (x2 - x1) * i / steps
                        val y = y1 + (y2 - y1) * i / steps
                        sleepUntil(target)
                        inject(auto, MotionEvent.obtain(downAt, target,
                            MotionEvent.ACTION_MOVE, x, y, 0))
                        liveLog("seq-move $target %.1f %.1f\n".format(x, y))
                    }
                    val upAt = wall + durMs
                    sleepUntil(upAt)
                    inject(auto, MotionEvent.obtain(downAt, upAt,
                        MotionEvent.ACTION_UP, x2, y2, 0))
                    liveLog("seq-end $upAt\n")
                }
                "sleep" -> Thread.sleep(p[1].toLong())
            }
        }
        done.appendText(log.toString())
    }

    private fun sleepUntil(target: Long) {
        var now = SystemClock.uptimeMillis()
        while (now < target) {
            try { Thread.sleep(target - now) } catch (ignored: InterruptedException) {}
            now = SystemClock.uptimeMillis()
        }
    }

    private fun inject(auto: android.app.UiAutomation, ev: MotionEvent) {
        auto.injectInputEvent(ev, true)
        ev.recycle()
    }
}
