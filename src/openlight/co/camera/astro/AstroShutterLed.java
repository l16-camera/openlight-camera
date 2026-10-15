package openlight.co.camera.astro;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import openlight.co.camera.managers.CameraManager;
import openlight.co.camera.managers.capture.CaptureManager;
import openlight.co.lib.utils.FeatureManager;
import openlight.co.lib.utils.LogUtil;

/**
 * The shutter-button light in astro mode, so the camera can be read from a step away in
 * the dark without looking at the screen.
 *
 *   beacon    — astro on, focus not locked, nothing being shot: short bright double
 *               flashes. The camera is not ready for the night yet, and it is easy to
 *               find on the ground.
 *   countdown — the self-timer runs (every series frame goes through it too): one
 *               flash a second, quick flashes in the last second.
 *   capture   — a frame is being taken: steady dim red, so it does not light the scene
 *               or the observer's eyes.
 *   off       — focus locked and idle, or a series between frames.
 *
 * The light is the PMIC's RGB channel set (/sys/class/leds/{red,green,blue}, qpnp LPG).
 * Only red is used. brightness is world-writable on the L16 and SELinux is permissive;
 * the hardware blink and LUT files are system-only, so the pattern is timed here, on
 * its own thread. Whatever the LED showed before astro is put back on leaving.
 *
 * features.prop: astro.led (true), astro.led.beacon (255), astro.led.timer (160),
 * astro.led.capture (12), astro.led.beacon_period_ms (2500); levels 0..255.
 */
public final class AstroShutterLed {
    private static final String TAG = "AstroShutterLed";
    private static final String LEDS = "/sys/class/leds/";
    private static final String[] RGB = {"red", "green", "blue"};

    /** Tick while something blinks; slower while the level holds. */
    private static final long FAST_MS = 20;
    private static final long SLOW_MS = 200;
    /** Rewrite a held level now and then: the system lights service may take the LED. */
    private static final long REFRESH_MS = 2000;

    private enum Mode { OFF, BEACON, COUNTDOWN, CAPTURE }

    private static HandlerThread sThread;
    private static Handler sHandler;
    /** r, g, b before astro took the LED; null while we do not hold it. */
    private static int[] sSaved;
    private static Mode sMode = Mode.OFF;
    private static long sModeSince;
    private static int sLevel = -1;
    private static long sWritten;

    private static int sBeacon;
    private static int sTimer;
    private static int sCapture;
    private static long sBeaconPeriod;

    private static final Runnable TICK = new Runnable() {
        public void run() {
            long next = tick();
            Handler h = sHandler;
            if (h != null) {
                h.postDelayed(this, next);
            }
        }
    };

    private AstroShutterLed() {
    }

    public static synchronized void attach() {
        if (sThread != null || !new File(LEDS + "red/brightness").exists()) {
            return;
        }
        FeatureManager f = FeatureManager.get();
        sBeacon = clamp(f.getInt("astro.led.beacon", 255));
        sTimer = clamp(f.getInt("astro.led.timer", 160));
        sCapture = clamp(f.getInt("astro.led.capture", 12));
        sBeaconPeriod = Math.max(500, f.getInt("astro.led.beacon_period_ms", 2500));
        sThread = new HandlerThread("astro-led");
        sThread.start();
        sHandler = new Handler(sThread.getLooper());
        sHandler.post(TICK);
    }

    public static synchronized void detach() {
        if (sThread == null) {
            return;
        }
        final Handler h = sHandler;
        final HandlerThread t = sThread;
        sHandler = null;
        sThread = null;
        h.removeCallbacks(TICK);
        h.post(new Runnable() {
            public void run() {
                release();
                t.quit();
            }
        });
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** One step; returns the delay to the next. LED thread. */
    private static long tick() {
        boolean on = FeatureManager.get().getBoolean("astro.led", true)
                && CameraManager.getCameraModeOrDefault().isAstro();
        if (!on) {
            release();
            return SLOW_MS;
        }
        long now = SystemClock.uptimeMillis();
        if (sSaved == null) {
            sSaved = new int[3];
            for (int i = 0; i < 3; i++) {
                sSaved[i] = read(RGB[i]);
            }
            write("green", 0);
            write("blue", 0);
            sLevel = -1;
            LogUtil.i(TAG, "[ASTRO] shutter light taken, was rgb " + sSaved[0] + "/" + sSaved[1] + "/" + sSaved[2]);
        }
        CaptureManager cm = CaptureManager.get();
        long left = cm != null ? cm.getTimerRemainingMs() : 0;
        Mode mode = mode(cm, left);
        if (mode != sMode) {
            LogUtil.i(TAG, "[ASTRO] shutter light " + sMode + " -> " + mode);
            sMode = mode;
            sModeSince = now;
        }
        long t = now - sModeSince;
        int level;
        long next = FAST_MS;
        switch (mode) {
            case BEACON: {
                // Double flash: 70 on, 130 off, 70 on, then dark for the rest of the period.
                long p = t % sBeaconPeriod;
                level = p < 70 || (p >= 200 && p < 270) ? sBeacon : 0;
                break;
            }
            case COUNTDOWN: {
                // Counted from the timer's end, so the flashes land on whole seconds left.
                long ms = left % 1000;
                if (left < 1000) {
                    level = left % 250 >= 150 ? sTimer : 0;
                } else {
                    level = ms >= 850 ? sTimer : 0;
                }
                break;
            }
            case CAPTURE:
                level = sCapture;
                next = SLOW_MS;
                break;
            default:
                level = 0;
                next = SLOW_MS;
                break;
        }
        if (level != sLevel || now - sWritten > REFRESH_MS) {
            write("red", level);
            sLevel = level;
            sWritten = now;
        }
        return next;
    }

    private static Mode mode(CaptureManager cm, long timerLeft) {
        if (timerLeft > 0) {
            return Mode.COUNTDOWN;
        }
        if (cm != null && !cm.isIdle()) {
            return Mode.CAPTURE;
        }
        if (AstroSeries.isRunning() || AstroFocusGuard.get().isLocked()) {
            return Mode.OFF;
        }
        return Mode.BEACON;
    }

    /** Give the LED back as it was. LED thread. */
    private static void release() {
        if (sSaved == null) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            write(RGB[i], sSaved[i]);
        }
        LogUtil.i(TAG, "[ASTRO] shutter light released");
        sSaved = null;
        sMode = Mode.OFF;
        sLevel = -1;
    }

    private static int read(String led) {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(LEDS + led + "/brightness", "r");
            return Integer.parseInt(f.readLine().trim());
        } catch (IOException | RuntimeException e) {
            return 0;
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void write(String led, int value) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(LEDS + led + "/brightness");
            out.write((value + "\n").getBytes());
        } catch (IOException e) {
            LogUtil.w(TAG, "[ASTRO] " + led + " write failed: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
