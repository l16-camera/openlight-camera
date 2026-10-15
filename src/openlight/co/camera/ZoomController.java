package openlight.co.camera;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Choreographer;
import java.util.List;
import openlight.co.camera.managers.CameraManager;
import openlight.co.camera.managers.autoexposure.AutoExposureManager;
import openlight.co.camera.managers.capture.CaptureManager;
import openlight.co.camera.managers.focus.FocusManager;
import openlight.co.camera.managers.mono.MonoMode;
import openlight.co.camera.managers.zoom.ZoomManager;
import openlight.co.camera.view.zoom.ZoomWheel;
import openlight.co.lib.utils.LogUtil;
import openlight.co.touchstrip.TouchStrip;

/**
 * Continuous zoom for the touch strip and the one-finger screen scroll.
 *
 * Light's zoom moved in 1 mm detents, one capture request per detent: 122 of them
 * between 28 and 150 mm, each costing a fixed amount of finger travel. A millimetre is
 * 3.6 % of the frame at 28 mm and 0.7 % at 150 mm, so the long end barely moved, and a
 * quick swipe asked for more detents than the request path could carry — the strip
 * capped its backlog and threw the excess away, which is why acceleration was not felt.
 *
 * Here the zoom is one number, the log of the zoom ratio, so equal finger travel makes
 * an equal change of framing anywhere in the range. Input only moves a target; a frame
 * loop eases the published value toward it and sends at most one request per
 * PUBLISH_INTERVAL_MS, always the latest. Momentum, hold-to-zoom on the strip ends and
 * soft detents on the simulated primes all act on the same target.
 *
 * Package openlight.co.camera so it can reach BasePreviewFragment's protected zoom UI.
 */
public final class ZoomController implements Choreographer.FrameCallback {
    private static final String TAG = "ZoomController";

    /** A slow pass along the whole strip (768 units) doubles or halves the focal length. */
    private static final float STRIP_LOG_PER_UNIT = (float) (Math.log(2.0) / 768.0);

    /** Same for the screen: a slow 800 px scroll doubles the focal length. */
    private static final float SCREEN_LOG_PER_PX = (float) (Math.log(2.0) / 800.0);

    /**
     * Pointer-style acceleration: flat below the floor so slow placement stays precise,
     * quadratic up to the max gain at the ceiling. At full gain one brisk pass of the strip
     * covers about 2.4 stops — the whole 28-150 mm range is 2.4.
     */
    private static final float STRIP_SPEED_FLOOR = 350.0F;
    private static final float STRIP_SPEED_CEILING = 2600.0F;
    private static final float SCREEN_SPEED_FLOOR = 500.0F;
    private static final float SCREEN_SPEED_CEILING = 3000.0F;
    private static final float STRIP_MAX_GAIN = 3.5F;

    /**
     * The screen gets more: a flick on it is shorter and coarser than on the strip, and
     * a brisk 600 px stroke should still cross most of the range.
     */
    private static final float SCREEN_MAX_GAIN = 5.0F;

    /**
     * Weight of the newest sample in the screen speed estimate. High, because a flick is
     * only a handful of callbacks long and a lagging estimate leaves most of it at gain 1.
     */
    private static final float SPEED_SMOOTHING = 0.6F;

    /** Time constant of the published value chasing the target. */
    private static final float FOLLOW_TAU_S = 0.045F;

    /** Close enough to the target to stop easing, in log units (0.05 %). */
    private static final float SETTLE_EPSILON = 0.0005F;

    /** Smallest change worth a capture request, in log units (0.1 % of framing). */
    private static final float PUBLISH_EPSILON = 0.001F;

    /** One capture request per preview frame at most; the HAL runs preview at 30 fps. */
    private static final long PUBLISH_INTERVAL_MS = 33L;

    /** Strip release faster than this coasts; at the fling threshold it jumps a prime instead. */
    private static final float MOMENTUM_MIN_STRIP_SPEED = 900.0F;
    private static final float FLING_STRIP_SPEED = 6000.0F;
    private static final float MOMENTUM_TAU_S = 0.11F;
    private static final float MOMENTUM_STOP = 0.2F;

    /** Holding a strip end: starts at HOLD_START, reaches HOLD_MAX after HOLD_RAMP_S (log units/s). */
    private static final float HOLD_START = 0.5F;
    private static final float HOLD_MAX = 2.2F;
    private static final float HOLD_RAMP_S = 0.7F;

    /** A rest this close to a simulated prime (2.5 %) settles onto it, once the finger is up. */
    private static final float DETENT_RANGE = 0.025F;
    private static final long DETENT_DELAY_MS = 120L;

    /** No input for this long and nothing moving: the gesture is over. */
    private static final long SETTLE_DELAY_MS = 250L;

    /**
     * Mono has two stops and nothing worth framing between them, so a drag does not
     * crawl through the crop range: it banks travel, and once that passes MONO_SWITCH
     * (about 90 strip units or 90 px) the zoom goes straight to the stop that way. One
     * switch per gesture; lift and swipe again to come back.
     */
    private static final float MONO_SWITCH = 0.08F;

    /** In mono mode, travel past this (15 %) toward the other stop commits to it. */
    private static final float MONO_COMMIT = 0.15F;

    /** A prime jump must move at least this far, so a tap at a prime goes to the next one. */
    private static final float PRIME_STEP_MIN = 0.01F;

    private static ZoomController sInstance;

    private BasePreviewFragment mFragment;
    private final ZoomManager mZoomManager = ZoomManager.get();
    private final CameraInfo mCameraInfo = CameraInfo.get();

    private boolean mActive;
    private boolean mFrameScheduled;
    private long mLastFrameNanos;

    private float mCurrent;
    /** Mono: travel banked in this gesture, and whether it already switched stops. */
    private float mMonoTravel;
    private boolean mMonoSwitched;
    /** Where the gesture started, for the mono commit rule. */
    private float mStart;
    private float mTarget;
    private float mPublished;
    private long mLastPublishMs;
    private float mVelocity;
    private int mHoldDir;
    private float mHoldTime;
    private long mLastInputMs;
    /** A finger is on the strip or the screen; detents and the end of gesture wait for it. */
    private boolean mFingerDown;
    private boolean mDetentApplied;

    private long mLastScreenMs;
    private float mScreenSpeed;

    private int mRequests;
    private long mGestureStartMs;

    private final TouchStrip.OnTouchStripEventListener mStripEvents = new TouchStrip.OnTouchStripEventListener() {
        public void onEvent(TouchStrip.Event event) {
            switch (event) {
                case TAP_RIGHT:
                case FLING_RIGHT:
                    jumpToPrime(true);
                    break;
                case TAP_LEFT:
                case FLING_LEFT:
                    jumpToPrime(false);
                    break;
                case LONG_PRESS_START_RIGHT:
                    startHold(1);
                    break;
                case LONG_PRESS_START_LEFT:
                    startHold(-1);
                    break;
                case LONG_PRESS_END:
                    endHold();
                    break;
                default:
                    break;
            }
        }
    };

    private final TouchStrip.DragListener mStripDrag = new TouchStrip.DragListener() {
        public boolean onStripDown() {
            mFingerDown = true;
            newGesture();
            return brake();
        }

        public void onStripDrag(float dx, float speed) {
            float gain = gain(speed, STRIP_SPEED_FLOOR, STRIP_SPEED_CEILING, STRIP_MAX_GAIN);
            move(dx * STRIP_LOG_PER_UNIT * gain, ZoomWheel.EventType.TOUCHSTRIP);
        }

        public void onStripUp(float velocity, float travelScale) {
            fingerUp();
            float speed = Math.abs(velocity);
            if (speed < MOMENTUM_MIN_STRIP_SPEED || speed >= FLING_STRIP_SPEED || !mActive
                    || MonoMode.isActive()) {
                return;
            }
            mVelocity = velocity * travelScale * STRIP_LOG_PER_UNIT
                    * gain(speed, STRIP_SPEED_FLOOR, STRIP_SPEED_CEILING, STRIP_MAX_GAIN);
            mDetentApplied = false;
            schedule();
        }
    };

    public static synchronized ZoomController get() {
        if (sInstance == null) {
            sInstance = new ZoomController();
        }
        return sInstance;
    }

    /** Called from BasePreviewFragment when it registers for touch strip events. */
    public static void attach(BasePreviewFragment fragment) {
        ZoomController c = get();
        c.halt();
        c.mFragment = fragment;
        TouchStrip strip = TouchStrip.get(CameraApp.get());
        strip.unregisterEventListener(c.mStripEvents);
        strip.registerEventListener(c.mStripEvents);
        strip.setDragListener(c.mStripDrag);
    }

    public static void detach() {
        ZoomController c = get();
        c.halt();
        c.mFragment = null;
        TouchStrip strip = TouchStrip.get(CameraApp.get());
        strip.unregisterEventListener(c.mStripEvents);
        strip.setDragListener(null);
    }

    /** One-finger vertical scroll on the preview; distanceY is GestureDetector's, positive upwards. */
    public static void onScreenScroll(BasePreviewFragment fragment, float distanceY) {
        ZoomController c = get();
        if (c.mFragment != fragment) {
            c.mFragment = fragment;
        }
        long now = SystemClock.uptimeMillis();
        long dt = now - c.mLastScreenMs;
        c.mLastScreenMs = now;
        if (dt > 100L) {
            c.mScreenSpeed = 0.0F;
        } else {
            float sample = Math.abs(distanceY) * 1000.0F / Math.max(dt, 8L);
            c.mScreenSpeed += SPEED_SMOOTHING * (sample - c.mScreenSpeed);
        }
        float gain = gain(c.mScreenSpeed, SCREEN_SPEED_FLOOR, SCREEN_SPEED_CEILING, SCREEN_MAX_GAIN);
        c.move(distanceY * SCREEN_LOG_PER_PX * gain, ZoomWheel.EventType.DISPLAY);
    }

    /** Every touch on the preview, so the controller knows when the finger is down. */
    public static void onScreenTouch(MotionEvent event) {
        ZoomController c = get();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                c.mFingerDown = true;
                c.newGesture();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                c.fingerUp();
                break;
            default:
                break;
        }
    }

    private void newGesture() {
        mMonoTravel = 0.0F;
        mMonoSwitched = false;
    }

    private void fingerUp() {
        mFingerDown = false;
        if (mActive) {
            schedule();
        }
    }

    private static float gain(float speed, float floor, float ceiling, float maxGain) {
        if (speed <= floor) {
            return 1.0F;
        }
        if (speed >= ceiling) {
            return maxGain;
        }
        float t = (speed - floor) / (ceiling - floor);
        return 1.0F + t * t * (maxGain - 1.0F);
    }

    private void move(float dLog, ZoomWheel.EventType type) {
        if (!begin(type)) {
            return;
        }
        mVelocity = 0.0F;
        if (MonoMode.isActive()) {
            if (!mMonoSwitched) {
                mMonoTravel += dLog;
                if (Math.abs(mMonoTravel) > MONO_SWITCH) {
                    mMonoSwitched = true;
                    mTarget = monoEnd(mMonoTravel > 0.0F);
                }
            }
            touched();
            return;
        }
        mTarget = clamp(mTarget + dLog);
        touched();
    }

    /** The mono stop on the long (true) or wide (false) side. */
    private float monoEnd(boolean tele) {
        float end = tele ? -Float.MAX_VALUE : Float.MAX_VALUE;
        for (Float p : primes()) {
            end = tele ? Math.max(end, log(p)) : Math.min(end, log(p));
        }
        return clamp(end);
    }

    private void startHold(int dir) {
        if (!begin(ZoomWheel.EventType.TOUCHSTRIP)) {
            return;
        }
        mVelocity = 0.0F;
        if (MonoMode.isActive()) {
            // Holding an end in mono means "that stop", not a slow crawl to it.
            mTarget = monoEnd(dir > 0);
            touched();
            return;
        }
        mHoldDir = dir;
        mHoldTime = 0.0F;
        touched();
    }

    private void endHold() {
        mHoldDir = 0;
        touched();
    }

    private void jumpToPrime(boolean up) {
        if (!begin(ZoomWheel.EventType.TOUCHSTRIP)) {
            return;
        }
        mVelocity = 0.0F;
        mHoldDir = 0;
        float next = up ? log(maxRatio()) : 0.0F;
        for (Float p : primes()) {
            float lp = log(p);
            if (up && lp > mTarget + PRIME_STEP_MIN && lp < next) {
                next = lp;
            } else if (!up && lp < mTarget - PRIME_STEP_MIN && lp > next) {
                next = lp;
            }
        }
        mTarget = clamp(next);
        touched();
        mDetentApplied = true;
    }

    /** A finger on the strip catches a coast or a hold; returns whether anything was moving. */
    private boolean brake() {
        boolean moving = mActive && (mVelocity != 0.0F || mHoldDir != 0);
        if (moving) {
            mVelocity = 0.0F;
            mHoldDir = 0;
            mTarget = mCurrent;
            touched();
        }
        return moving;
    }

    private void touched() {
        mLastInputMs = SystemClock.uptimeMillis();
        mDetentApplied = false;
        schedule();
    }

    /** Starts a gesture if none is running. False when the camera is busy or not attached. */
    private boolean begin(ZoomWheel.EventType type) {
        if (mFragment == null || !interruptFocus()) {
            return false;
        }
        if (!mActive) {
            float level = mZoomManager.getZoomLevel();
            if (level <= 0.0F) {
                return false;
            }
            mCurrent = mTarget = mPublished = clamp(log(level));
            mStart = mCurrent;
            mVelocity = 0.0F;
            mHoldDir = 0;
            mLastFrameNanos = 0L;
            mRequests = 0;
            mGestureStartMs = SystemClock.uptimeMillis();
            mActive = true;
        }
        if (!mZoomManager.isCurrentlyZooming()) {
            mFragment.showZoomWheelView(type);
            mFragment.removeFocusCrossHair();
        }
        return true;
    }

    /**
     * The camera counts as busy while autofocus runs, and the post-zoom focus after a
     * module change can run for most of a second — long enough for the strip to feel
     * dead. A zoom gesture outranks that focus: cancel it, and have the fragment refocus
     * once the zoom settles. Capture and metering still win.
     */
    private boolean interruptFocus() {
        CameraManager cameraManager = CameraManager.get();
        if (cameraManager.isIdle()) {
            return true;
        }
        FocusManager focusManager = FocusManager.get();
        if (!cameraManager.isCameraInOpenState() || !CaptureManager.get().isIdle()
                || AutoExposureManager.get().isMetering() || !focusManager.isFocusing()) {
            return false;
        }
        LogUtil.d(TAG, "[ZOOM] interrupting autofocus");
        focusManager.cancelAfTrigger(cameraManager.getPreviewRequestBuilder());
        focusManager.resetFocusStateToIdle();
        mFragment.triggerFocusPostZoom();
        return cameraManager.isIdle();
    }

    private void schedule() {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    public void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;
        if (!mActive || mFragment == null) {
            return;
        }
        if (!CameraManager.get().isIdle() && !interruptFocus()) {
            // A capture or metering took over; stop where the preview is.
            finish();
            return;
        }
        float dt = mLastFrameNanos == 0L ? 0.016F
                : Math.min((frameTimeNanos - mLastFrameNanos) / 1.0e9F, 0.05F);
        mLastFrameNanos = frameTimeNanos;
        long now = SystemClock.uptimeMillis();

        if (mHoldDir != 0) {
            mHoldTime += dt;
            float rate = HOLD_START + (HOLD_MAX - HOLD_START) * Math.min(1.0F, mHoldTime / HOLD_RAMP_S);
            mTarget = clamp(mTarget + mHoldDir * rate * dt);
            mLastInputMs = now;
        }
        if (mVelocity != 0.0F) {
            float before = mTarget;
            mTarget = clamp(mTarget + mVelocity * dt);
            mVelocity *= (float) Math.exp(-dt / MOMENTUM_TAU_S);
            if (Math.abs(mVelocity) < MOMENTUM_STOP || mTarget == before) {
                mVelocity = 0.0F;
            }
            mLastInputMs = now;
        }
        if (!mFingerDown && mHoldDir == 0 && mVelocity == 0.0F && !mDetentApplied
                && now - mLastInputMs >= DETENT_DELAY_MS) {
            mTarget = detent(mTarget);
            mDetentApplied = true;
        }

        float a = 1.0F - (float) Math.exp(-dt / FOLLOW_TAU_S);
        mCurrent += (mTarget - mCurrent) * a;
        if (Math.abs(mTarget - mCurrent) < SETTLE_EPSILON) {
            mCurrent = mTarget;
        }

        boolean due = now - mLastPublishMs >= PUBLISH_INTERVAL_MS;
        boolean landed = mCurrent == mTarget && mPublished != mTarget;
        if ((due && Math.abs(mCurrent - mPublished) >= PUBLISH_EPSILON) || (landed && due)) {
            publish(mCurrent, now);
        }

        boolean resting = mHoldDir == 0 && mVelocity == 0.0F && mCurrent == mTarget
                && mPublished == mTarget;
        if (resting && mDetentApplied && now - mLastInputMs >= SETTLE_DELAY_MS) {
            finish();
            return;
        }
        if (resting && mFingerDown) {
            // Finger parked on the control: nothing to animate until it moves or lifts.
            return;
        }
        schedule();
    }

    private void publish(float value, long now) {
        mZoomManager.setZoomForTest(ratioFor(value));
        mZoomManager.performZoom(mFragment.mZoomWheel);
        mPublished = value;
        mLastPublishMs = now;
        mRequests++;
    }

    /** End of gesture: hand back to the fragment's own post-zoom path (hide wheel, refocus). */
    private void finish() {
        mActive = false;
        mVelocity = 0.0F;
        mHoldDir = 0;
        if (mFrameScheduled) {
            Choreographer.getInstance().removeFrameCallback(this);
            mFrameScheduled = false;
        }
        LogUtil.d(TAG, "[ZOOM] settled at " + Math.round(Math.exp(mPublished) * minFocal35()) + " mm, "
                + mRequests + " requests in " + (SystemClock.uptimeMillis() - mGestureStartMs) + " ms");
        if (mFragment != null) {
            mFragment.removeZoomWheelView();
            mFragment.doFocusPostZoom();
        }
    }

    private void halt() {
        if (mActive) {
            mActive = false;
            mVelocity = 0.0F;
            mHoldDir = 0;
        }
        if (mFrameScheduled) {
            Choreographer.getInstance().removeFrameCallback(this);
            mFrameScheduled = false;
        }
    }

    /**
     * Ratio handed to ZoomManager. At a lens boundary it must be the very float
     * ZoomManager compares against (lens / lens[0]), or a rest at 70 mm would stay on
     * the 28 mm group with a 2.5x crop.
     */
    private float ratioFor(float value) {
        float[] lenses = mCameraInfo.getLensesFocalLengths();
        if (lenses != null && lenses.length > 0) {
            for (int i = 0; i < mCameraInfo.getNumberOfLenses() && i < lenses.length; i++) {
                float r = lenses[i] / lenses[0];
                if (Math.abs(log(r) - value) < 1.0e-3F) {
                    return r;
                }
            }
        }
        for (Float p : primes()) {
            if (Math.abs(log(p) - value) < 1.0e-4F) {
                return p;
            }
        }
        return Math.max(1.0F, Math.min(maxRatio(), (float) Math.exp(value)));
    }

    /**
     * Nearest rest point within DETENT_RANGE: Light's simulated primes (28/35/75/150) and
     * the native focal of each module group (70 mm, uncropped B). Mono has its own rule.
     */
    private float detent(float value) {
        if (MonoMode.isActive()) {
            return monoStop(value);
        }
        float best = value;
        float bestDist = DETENT_RANGE;
        for (Float p : primes()) {
            float d = Math.abs(log(p) - value);
            if (d < bestDist) {
                bestDist = d;
                best = log(p);
            }
        }
        float[] lenses = mCameraInfo.getLensesFocalLengths();
        if (lenses != null && lenses.length > 0) {
            for (int i = 0; i < mCameraInfo.getNumberOfLenses() && i < lenses.length; i++) {
                float lp = log(lenses[i] / lenses[0]);
                float d = Math.abs(lp - value);
                if (d < bestDist) {
                    bestDist = d;
                    best = lp;
                }
            }
        }
        return clamp(best);
    }

    /**
     * Mono offers only 28 and 150 mm. Settling on the nearer one would need a drag past
     * 65 mm to reach the long end, so a clear move from where the gesture began commits
     * to the stop in that direction; a small one settles on the nearer stop.
     */
    private float monoStop(float value) {
        List<Float> stops = primes();
        if (stops.isEmpty()) {
            return value;
        }
        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        for (Float p : stops) {
            low = Math.min(low, log(p));
            high = Math.max(high, log(p));
        }
        if (value - mStart > MONO_COMMIT) {
            return clamp(high);
        }
        if (mStart - value > MONO_COMMIT) {
            return clamp(low);
        }
        return clamp(Math.abs(value - low) <= Math.abs(value - high) ? low : high);
    }

    private List<Float> primes() {
        return mCameraInfo.getSimulatedPrimeFocalLengthRatios();
    }

    private float maxRatio() {
        float max = mCameraInfo.getMaxToMinZoomRatio();
        return max > 1.0F ? max : 1.0F;
    }

    private float minFocal35() {
        return mCameraInfo.getMinFocalLengthLens() * 10.0F;
    }

    private float clamp(float value) {
        return Math.max(0.0F, Math.min(log(maxRatio()), value));
    }

    private static float log(float ratio) {
        return (float) Math.log(ratio);
    }
}
