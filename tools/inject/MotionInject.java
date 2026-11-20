// Precise-timestamp MotionEvent injection via InputManager.injectInputEvent.
// Runs under `app_process` as shell (uid 2000, has INJECT_EVENTS).
// usage:
//   MotionInject tap x y
//   MotionInject hold x y hold_ms
//   MotionInject down x y
//   MotionInject move x y
//   MotionInject up x y
//   MotionInject swipe x1 y1 x2 y2 steps duration_ms   (evenly spaced, exact eventTime)
//   MotionInject fling x1 y1 x2 y2 steps duration_ms   (same; last move < 56ms before up)
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

public class MotionInject {
  private static InputManager im;
  private static Method inject;
  private static final int WAIT_FOR_FINISH = 2;
  private static long downAt;

  private static void send(MotionEvent ev) throws Exception {
    inject.invoke(im, ev, WAIT_FOR_FINISH);
    ev.recycle();
  }

  private static void down(float x, float y) throws Exception {
    downAt = SystemClock.uptimeMillis();
    send(MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0));
  }

  private static void move(float x, float y) throws Exception {
    send(MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y, 0));
  }

  private static void up(float x, float y) throws Exception {
    send(MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0));
  }

  private static void timedSeq(float x1, float y1, float x2, float y2,
                               int steps, long durMs, boolean fling) throws Exception {
    downAt = SystemClock.uptimeMillis();
    long wall = downAt;
    send(MotionEvent.obtain(downAt, wall, MotionEvent.ACTION_DOWN, x1, y1, 0));
    for (int i = 1; i <= steps; i++) {
      long target = wall + (durMs * i / (fling ? (steps + 1) : steps));
      float x = x1 + (x2 - x1) * i / steps;
      float y = y1 + (y2 - y1) * i / steps;
      long now = SystemClock.uptimeMillis();
      if (now < target) {
        try { Thread.sleep(target - now); } catch (InterruptedException ignored) {}
      }
      send(MotionEvent.obtain(downAt, target, MotionEvent.ACTION_MOVE, x, y, 0));
    }
    long upAt;
    if (fling) {
      upAt = wall + durMs; // <56ms after last move per ref fling-pause check
    } else {
      upAt = wall + durMs;
    }
    long now = SystemClock.uptimeMillis();
    if (now < upAt) {
      try { Thread.sleep(upAt - now); } catch (InterruptedException ignored) {}
    }
    send(MotionEvent.obtain(downAt, upAt, MotionEvent.ACTION_UP, x2, y2, 0));
  }

  public static void main(String[] a) throws Exception {
    // InputManager.getInstance() is @hide; get the singleton reflectively.
    java.lang.reflect.Field f = InputManager.class.getDeclaredField("sInstance");
    f.setAccessible(true);
    im = (InputManager) f.get(null);
    if (im == null) {
      // older path: private constructor
      java.lang.reflect.Constructor<InputManager> c =
          InputManager.class.getDeclaredConstructor();
      c.setAccessible(true);
      im = c.newInstance();
    }
    inject = InputManager.class.getMethod(
        "injectInputEvent", InputEvent.class, int.class);
    String cmd = a[0];
    switch (cmd) {
      case "down": down(Float.parseFloat(a[1]), Float.parseFloat(a[2])); break;
      case "move": move(Float.parseFloat(a[1]), Float.parseFloat(a[2])); break;
      case "up":   up(Float.parseFloat(a[1]), Float.parseFloat(a[2]));   break;
      case "tap": {
        float x = Float.parseFloat(a[1]), y = Float.parseFloat(a[2]);
        down(x, y);
        try { Thread.sleep(60); } catch (InterruptedException ignored) {}
        up(x, y);
        break;
      }
      case "hold": {
        float x = Float.parseFloat(a[1]), y = Float.parseFloat(a[2]);
        long ms = Long.parseLong(a[3]);
        down(x, y);
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
        up(x, y);
        break;
      }
      case "swipe":
        timedSeq(Float.parseFloat(a[1]), Float.parseFloat(a[2]),
                 Float.parseFloat(a[3]), Float.parseFloat(a[4]),
                 Integer.parseInt(a[5]), Long.parseLong(a[6]), false);
        break;
      case "fling":
        timedSeq(Float.parseFloat(a[1]), Float.parseFloat(a[2]),
                 Float.parseFloat(a[3]), Float.parseFloat(a[4]),
                 Integer.parseInt(a[5]), Long.parseLong(a[6]), true);
        break;
      default:
        System.err.println("unknown " + cmd);
        System.exit(2);
    }
  }
}
