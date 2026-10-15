package openlight.co.camera.astro;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import openlight.co.camera.CameraApp;
import openlight.co.camera.managers.CameraManager;
import openlight.co.lib.utils.LogUtil;

/**
 * Focus lock that outlives the things that normally break it — in astro mode only.
 *
 * At night the ASIC's autofocus parks the 28 mm modules at 0.5–3 m (L16_00241, read
 * from lens_position), so focus has to be set beforehand on something distant and then
 * kept. Measured 2026-10-15: a screen lock (long press) already survives captures — two
 * frames in a row match to one or two hall codes — but not a trip out of the app: on
 * return SmartAFTriggerMgr.init resets the trigger source and "Initial focus in module"
 * refocuses on the dark scene (A2 moved 4128 → 11360, L16_00288 → L16_00289).
 *
 * The rule: once locked on a tripod the lock stays until the camera is actually moved —
 * the firmware drops its tripod flag (co.light.tripod_fw 1 → 0), or gravity in camera
 * axes turns by more than MOVE_DEG (re-aimed). Taps and zoom still release it, as they
 * always did, because those are the user asking for a new focus.
 */
public final class AstroFocusGuard implements SensorEventListener {
    private static final String TAG = "AstroFocusGuard";

    /**
     * Re-aim threshold. Stars sit at infinity whichever way the camera points, so a
     * small nudge does not defocus anything; this is about the user starting over.
     */
    private static final double MOVE_DEG = 3.0;

    /** Low-pass weight for the gravity estimate; the camera on a tripod barely moves. */
    private static final float GRAVITY_SMOOTHING = 0.1F;

    private static AstroFocusGuard sInstance;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mLocked;
    private boolean mListening;
    private final float[] mGravity = new float[3];
    private boolean mHaveGravity;
    private float[] mGravityAtLock;
    private Byte mLastTripod;

    public static synchronized AstroFocusGuard get() {
        if (sInstance == null) {
            sInstance = new AstroFocusGuard();
        }
        return sInstance;
    }

    private static boolean astro() {
        return CameraManager.getCameraModeOrDefault().isAstro();
    }

    public boolean isLocked() {
        return mLocked;
    }

    /** The user long-pressed to lock focus. */
    public void onScreenLock() {
        if (!astro()) {
            return;
        }
        mLocked = true;
        mGravityAtLock = mHaveGravity ? mGravity.clone() : null;
        listen(true);
        LogUtil.i(TAG, "[ASTRO] focus locked, tripod=" + mLastTripod
                + (mGravityAtLock == null ? ", gravity pending" : ""));
    }

    /** A tap, zoom or mode change asked for a new focus: the lock is the user's to drop. */
    public void onUserRefocus(String why) {
        if (mLocked) {
            release(why, false);
        }
    }

    /** co.light.tripod_fw from every preview result. */
    public void onTripod(Byte tripod) {
        if (mLocked && !astro()) {
            // Another mode refocuses as it likes; a lock carried back into astro would
            // be stale.
            release("left astro mode", false);
        }
        if (tripod == null) {
            return;
        }
        if (mLastTripod == null || !mLastTripod.equals(tripod)) {
            LogUtil.i(TAG, "[ASTRO] tripod_fw " + mLastTripod + " -> " + tripod);
        }
        boolean lost = mLastTripod != null && mLastTripod != 0 && tripod == 0;
        mLastTripod = tripod;
        if (lost && mLocked && astro()) {
            release("camera left the tripod", true);
        }
    }

    /**
     * Asked when the camera reopens and would run its initial focus. True means skip it
     * and keep the lens where the lock left it.
     */
    public boolean keepLockOnReopen() {
        if (!mLocked || !astro()) {
            return false;
        }
        if (mGravityAtLock != null && mHaveGravity) {
            double turned = angleDeg(mGravityAtLock, mGravity);
            if (turned > MOVE_DEG) {
                release(String.format("re-aimed by %.1f°", turned), true);
                return false;
            }
        }
        LogUtil.i(TAG, "[ASTRO] camera did not move: keeping focus lock across reopen");
        return true;
    }

    private void release(String why, boolean tell) {
        mLocked = false;
        mGravityAtLock = null;
        listen(false);
        LogUtil.i(TAG, "[ASTRO] focus lock released: " + why);
        if (tell) {
            final String text = "Фокус разблокирован: " + why;
            mMain.post(new Runnable() {
                public void run() {
                    Toast.makeText(CameraApp.get(), text, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void listen(boolean on) {
        SensorManager sm = (SensorManager) CameraApp.get().getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) {
            return;
        }
        if (on && !mListening) {
            Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) {
                // Kept while the app is in the background too: moving the camera then
                // must still count.
                mListening = sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
            }
        } else if (!on && mListening) {
            sm.unregisterListener(this);
            mListening = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mHaveGravity) {
            System.arraycopy(event.values, 0, mGravity, 0, 3);
            mHaveGravity = true;
        } else {
            for (int i = 0; i < 3; i++) {
                mGravity[i] += GRAVITY_SMOOTHING * (event.values[i] - mGravity[i]);
            }
        }
        if (!mLocked) {
            return;
        }
        if (mGravityAtLock == null) {
            mGravityAtLock = mGravity.clone();
            return;
        }
        double turned = angleDeg(mGravityAtLock, mGravity);
        if (turned > MOVE_DEG) {
            release(String.format("re-aimed by %.1f°", turned), true);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static double angleDeg(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < 3; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        double c = Math.max(-1.0, Math.min(1.0, dot / Math.sqrt(na * nb)));
        return Math.toDegrees(Math.acos(c));
    }
}
