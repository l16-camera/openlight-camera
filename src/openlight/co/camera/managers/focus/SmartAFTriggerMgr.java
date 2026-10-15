package openlight.co.camera.managers.focus;

import android.graphics.Rect;
import android.hardware.camera2.params.Face;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import com.immersion.ImmVibeAPI;
import java.util.List;
import openlight.co.camera.CameraInfo;
import openlight.co.camera.astro.AstroFocusGuard;
import openlight.co.camera.listener.HardKeyManager;
import openlight.co.camera.listener.SignificantMotionDetector;
import openlight.co.camera.listener.SignificantMotionListener;
import openlight.co.camera.managers.CameraManager;
import openlight.co.camera.managers.zoom.ZoomManager;
import openlight.co.camera.metrics.CameraMetrics;
import openlight.co.camera.metrics.Metrics;
import openlight.co.camera.utils.CamPrefsUtils;
import openlight.co.camera.utils.Provider;
import openlight.co.camera.utils.TimingLoggerUtil;
import openlight.co.camera.view.ftu.FtuHelper;
import openlight.co.lib.utils.FeatureManager;
import openlight.co.lib.utils.LogUtil;
import openlight.co.lib.utils.Utils;

public class SmartAFTriggerMgr implements SignificantMotionListener {
    private static final int FOCUS_TRIGGER_OFFSET = 250;
    private static final int MAX_FACES_BATCH_COUNT = 3;
    private static final int MSG_ENABLE_SIGNIFICATION_MOTION_DETECTION = 2;
    private static final int MSG_NO_MORE_FACES_IN_SCENE = 3;
    private static final int MSG_RESUME_FACE_PROCESSING_FOR_FOCUS = 1;
    private static final int NUM_FACES_REQUIRED_FOR_FOCUS_TRIGGER = 1;

    private static final String TAG = Utils.safeTag(SmartAFTriggerMgr.class);
    private static final SmartAFTriggerMgr sInstance = new SmartAFTriggerMgr();
    private static final int MINIMUM_FACE_SCORE_FOR_FOCUS_TRIGGER =
            FeatureManager.get().getInt("min.face.score", 50);
    private static final int THRESHOLD_PERCENT_CHANGE_IN_AREA_AT_MIN_ZOOM =
            FeatureManager.get().getInt("face.area.change.min.zoom", 25);
    private static final int THRESHOLD_PERCENT_CHANGE_IN_AREA_AT_MAX_ZOOM =
            FeatureManager.get().getInt("face.area.change.max.zoom", 10);
    private static final long PAUSE_MOTION_DETECT_POST_USER_TAP_TIME =
            FeatureManager.get().getInt("caf.disabled.post.tap", ImmVibeAPI.VIBE_EDITION_5000);
    private static final long PAUSE_FACE_PROCESSING_POST_FACE_FOCUS_TRIGGER_TIME =
            FeatureManager.get().getInt("pause.face.post.focus", 2500);
    private static final int THRESHOLD_FACE_MOVED_DISTANCE =
            FeatureManager.get().getInt("face.distance", 300);
    private static final int MAX_ALLOWED_INTER_FRAME_FACE_MOVEMENT =
            FeatureManager.get().getInt("face.interframe.dist", 12);

    private final Provider<CameraManager> mCameraManager = CameraManager::get;
    private final Provider<ZoomManager> mZoomManager = ZoomManager::get;
    private final TimingLoggerUtil mTimingLoggerUtil = TimingLoggerUtil.get();
    private final Metrics mMetrics = Metrics.get();
    private final FtuHelper mFtuHelper = FtuHelper.get();
    private final SignificantMotionDetector mSignificantMotionDetector =
            SignificantMotionDetector.get();
    private final Handler mMainLooperHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_RESUME_FACE_PROCESSING_FOR_FOCUS:
                    mPauseFaceProcessingPostFaceFocusTrigger = false;
                    break;
                case MSG_ENABLE_SIGNIFICATION_MOTION_DETECTION:
                    initSignificantMotionDetector();
                    break;
                case MSG_NO_MORE_FACES_IN_SCENE:
                    triggerFocusAtCenter(FocusTriggerSource.FACE_DETECTION);
                    break;
            }
        }
    };

    private volatile boolean mAfdModeEnabled;
    private volatile Face mLastFocusTriggeredFace;
    private Face mLastQualifiedFace;
    private volatile boolean mPauseFaceProcessingPostFaceFocusTrigger;
    private boolean mSignificantMotionDetectorRegistered;
    private int mNumberOfFacesSeenPreviously = 0;
    private int mFacesBatchCount = 0;
    private volatile FocusTriggerSource mMostRecentFocusTriggerSource = FocusTriggerSource.SYSTEM;
    private final float mFaceAreaToZoomSlopeFactor =
            (THRESHOLD_PERCENT_CHANGE_IN_AREA_AT_MAX_ZOOM
                    - THRESHOLD_PERCENT_CHANGE_IN_AREA_AT_MIN_ZOOM)
                    / (CameraInfo.get().getMaxToMinZoomRatio() - 1.0f);

    public enum AutoFocusMode {
        AFD,
        AFS
    }

    private enum FocusTriggerSource {
        SYSTEM(1),
        FACE_DETECTION(2),
        SIGNIFICATION_MOTION(3),
        ZOOM(4),
        USER(5),
        USER_HW(6),
        TEST(7),
        USER_SCREEN_LOCK(8),
        USER_HW_LOCK(9);

        private final int mFocusTypeId;

        FocusTriggerSource(int focusTypeId) {
            mFocusTypeId = focusTypeId;
        }

        public int getFocusTriggerId() {
            return mFocusTypeId;
        }
    }

    public static SmartAFTriggerMgr get() {
        return sInstance;
    }

    private SmartAFTriggerMgr() {
    }

    public void init() {
        mAfdModeEnabled = checkAndUpdateAfMode();
        if (mAfdModeEnabled) {
            cleanupLocalState();
            initSignificantMotionDetector();
        } else {
            releaseSignificantMotionDetector();
        }
    }

    public void release() {
        updateAndRelease();
        mAfdModeEnabled = false;
        cleanupLocalState();
    }

    private void cleanupLocalState() {
        mMainLooperHandler.removeMessages(MSG_RESUME_FACE_PROCESSING_FOR_FOCUS);
        mMainLooperHandler.removeMessages(MSG_ENABLE_SIGNIFICATION_MOTION_DETECTION);
        mNumberOfFacesSeenPreviously = 0;
        mLastFocusTriggeredFace = null;
        mPauseFaceProcessingPostFaceFocusTrigger = false;
        mMostRecentFocusTriggerSource = FocusTriggerSource.SYSTEM;
    }

    public void updateAndRelease() {
        mAfdModeEnabled = checkAndUpdateAfMode();
        releaseSignificantMotionDetector();
    }

    public boolean isFaceBasedTriggerAppropriate() {
        return mMostRecentFocusTriggerSource != FocusTriggerSource.USER
                && mMostRecentFocusTriggerSource != FocusTriggerSource.USER_SCREEN_LOCK
                && mMostRecentFocusTriggerSource != FocusTriggerSource.USER_HW_LOCK;
    }

    public void processSystemTrigger() {
        mTimingLoggerUtil.captureTiming(TimingLoggerUtil.TimeToAutoFocusSplits.INTERNAL_TRIGGER);
        mCameraManager.get().triggerAeFocusFirstTime();
        mMostRecentFocusTriggerSource = FocusTriggerSource.SYSTEM;
    }

    public void processZoomTrigger() {
        AstroFocusGuard.get().onUserRefocus("zoom");
        if (mLastFocusTriggeredFace != null
                && mMostRecentFocusTriggerSource == FocusTriggerSource.FACE_DETECTION) {
            LogUtil.i(TAG, "Ignoring focus trigger for this zoom since faces are in the scene, "
                    + "and face detect will trigger focus");
        } else {
            mTimingLoggerUtil.captureTiming(
                    TimingLoggerUtil.TimeToAutoFocusSplits.INTERNAL_TRIGGER);
            triggerFocusAtCenter(FocusTriggerSource.ZOOM);
        }
    }

    public void processUserTap(float x, float y) {
        AstroFocusGuard.get().onUserRefocus("tap to focus");
        releaseSignificantMotionDetector();
        mTimingLoggerUtil.captureTiming(TimingLoggerUtil.TimeToAutoFocusSplits.SOFT_ROI_TAP);
        triggerFocus(FocusTriggerSource.USER, (int) x, (int) y);
        mMainLooperHandler.sendEmptyMessageDelayed(MSG_ENABLE_SIGNIFICATION_MOTION_DETECTION,
                PAUSE_MOTION_DETECT_POST_USER_TAP_TIME);
    }

    public void processTest(int x, int y) {
        mTimingLoggerUtil.captureTiming(TimingLoggerUtil.TimeToAutoFocusSplits.TEST);
        if (x == 0 && y == 0) {
            triggerFocusAtCenter(FocusTriggerSource.TEST);
        } else {
            triggerFocus(FocusTriggerSource.TEST, x, y);
        }
    }

    public Face getLastFocusTriggeredFace() {
        if (mMostRecentFocusTriggerSource == FocusTriggerSource.FACE_DETECTION) {
            return mLastFocusTriggeredFace;
        }
        return null;
    }

    public void processHardKeyFocus(HardKeyManager.KeyAction keyAction) {
        LogUtil.d(TAG, "HW key action: " + keyAction);
        switch (keyAction) {
            case DOWN:
                mMostRecentFocusTriggerSource = FocusTriggerSource.USER_HW;
                mCameraManager.get().triggerAeFocusAtLastPoint();
                break;
            case UP:
                if (mMostRecentFocusTriggerSource == FocusTriggerSource.USER_HW_LOCK) {
                    resetTriggerSourceAndEnableAfd();
                }
                break;
            case LONG_PRESS:
                releaseSignificantMotionDetector();
                mMostRecentFocusTriggerSource = FocusTriggerSource.USER_HW_LOCK;
                mMetrics.add(CameraMetrics.EVENT_FOCUS_LOCKED_HW_KEY);
                break;
        }
    }

    public int getFocusTriggerType() {
        return mMostRecentFocusTriggerSource.getFocusTriggerId();
    }

    public void focusComplete() {
        if (mMostRecentFocusTriggerSource == FocusTriggerSource.USER_HW) {
            resetTriggerSourceAndEnableAfd();
        }
    }

    public void stillCaptureCompleted() {
        if (mMostRecentFocusTriggerSource != FocusTriggerSource.USER_SCREEN_LOCK) {
            if (mMostRecentFocusTriggerSource != FocusTriggerSource.FACE_DETECTION) {
                mMostRecentFocusTriggerSource = FocusTriggerSource.SYSTEM;
            }
            mPauseFaceProcessingPostFaceFocusTrigger = false;
            initSignificantMotionDetector();
        }
    }

    public void processFaces(List<Face> faces) {
        if (!mAfdModeEnabled || !isFaceBasedTriggerAppropriate() || mFtuHelper.isFtuPlaying()) {
            return;
        }
        int faceCount = faces.size();
        if (faceCount == 0) {
            initSignificantMotionDetector();
            if (mLastFocusTriggeredFace == null) {
                return;
            }
        } else {
            releaseSignificantMotionDetector();
        }
        if (mPauseFaceProcessingPostFaceFocusTrigger || !isFaceBasedTriggerAppropriate()) {
            return;
        }
        Face qualifiedFace = getQualifiedFace(faces);
        boolean faceStationary = false;
        if (mNumberOfFacesSeenPreviously != faceCount) {
            mNumberOfFacesSeenPreviously = faceCount;
            mFacesBatchCount = 0;
            if (qualifiedFace == null
                    || (mLastFocusTriggeredFace != null
                            && mLastFocusTriggeredFace.getId() != qualifiedFace.getId())) {
                mLastFocusTriggeredFace = null;
            }
            mLastQualifiedFace = qualifiedFace;
            return;
        }
        if (qualifiedFace != null && mLastQualifiedFace != null) {
            faceStationary = isFaceStationary(
                    mLastQualifiedFace.getBounds(), qualifiedFace.getBounds());
        } else if (faceCount == 0) {
            faceStationary = true;
        }
        mLastQualifiedFace = qualifiedFace;
        if (!faceStationary) {
            return;
        }
        mFacesBatchCount++;
        if (mFacesBatchCount == MAX_FACES_BATCH_COUNT) {
            changeInFaceCountBasedFocusTrigger(qualifiedFace, faceCount);
        } else if (mFacesBatchCount > MAX_FACES_BATCH_COUNT && faceCount > 0) {
            faceAreaOrPositionChangeFocusTrigger(qualifiedFace);
        }
    }

    private boolean isFaceStationary(Rect previousBounds, Rect currentBounds) {
        return Math.abs(currentBounds.left - previousBounds.left)
                        < MAX_ALLOWED_INTER_FRAME_FACE_MOVEMENT
                && Math.abs(currentBounds.top - previousBounds.top)
                        < MAX_ALLOWED_INTER_FRAME_FACE_MOVEMENT;
    }

    public void processScreenLongPress(float x, float y) {
        releaseSignificantMotionDetector();
        mMostRecentFocusTriggerSource = FocusTriggerSource.USER_SCREEN_LOCK;
        triggerFocus(FocusTriggerSource.USER_SCREEN_LOCK, (int) x, (int) y);
        mMetrics.add(CameraMetrics.EVENT_FOCUS_LOCKED_HW_KEY);
        AstroFocusGuard.get().onScreenLock();
    }

    /**
     * Put back a screen lock that init() wiped on reopen, without refocusing: the lens is
     * still where the lock left it. Used by astro mode when the camera did not move.
     */
    public void restoreScreenLock() {
        releaseSignificantMotionDetector();
        mMostRecentFocusTriggerSource = FocusTriggerSource.USER_SCREEN_LOCK;
    }

    public boolean isFocusLocked() {
        return mMostRecentFocusTriggerSource == FocusTriggerSource.USER_SCREEN_LOCK
                || mMostRecentFocusTriggerSource == FocusTriggerSource.USER_HW_LOCK;
    }

    public void focusFailed() {
        if (isAfModeSettingAfd()) {
            mLastFocusTriggeredFace = null;
            mPauseFaceProcessingPostFaceFocusTrigger = false;
            initSignificantMotionDetector();
            mMainLooperHandler.removeMessages(MSG_ENABLE_SIGNIFICATION_MOTION_DETECTION);
            mMainLooperHandler.removeMessages(MSG_RESUME_FACE_PROCESSING_FOR_FOCUS);
            mMostRecentFocusTriggerSource = FocusTriggerSource.SYSTEM;
        }
    }

    private void faceAreaOrPositionChangeFocusTrigger(Face face) {
        if (face == null) {
            return;
        }
        if (mLastFocusTriggeredFace == null) {
            mLastFocusTriggeredFace = face;
            return;
        }
        Rect bounds = face.getBounds();
        float currentArea = bounds.width() * bounds.height();
        Rect lastBounds = mLastFocusTriggeredFace.getBounds();
        float lastArea = lastBounds.width() * lastBounds.height();
        float zoomThreshold = (mFaceAreaToZoomSlopeFactor
                * (mZoomManager.get().getZoomLevel() - 1.0f))
                + THRESHOLD_PERCENT_CHANGE_IN_AREA_AT_MIN_ZOOM;
        float areaChangePercent = Math.abs(((currentArea - lastArea) * 100.0f) / lastArea);
        if (areaChangePercent > zoomThreshold) {
            LogUtil.d(TAG, "Change in face area in percent : " + areaChangePercent
                    + " Last Focus Triggered Face " + mLastFocusTriggeredFace);
            triggerFocusWithFace(face);
            return;
        }
        int deltaX = bounds.left - lastBounds.left;
        int deltaY = bounds.top - lastBounds.top;
        if (Math.sqrt((deltaX * deltaX) + (deltaY * deltaY)) > THRESHOLD_FACE_MOVED_DISTANCE) {
            LogUtil.d(TAG, "Face moved in preview, last face : " + mLastFocusTriggeredFace);
            triggerFocusWithFace(face);
        }
    }

    private void changeInFaceCountBasedFocusTrigger(Face face, int faceCount) {
        if (faceCount < NUM_FACES_REQUIRED_FOR_FOCUS_TRIGGER) {
            mMainLooperHandler.sendEmptyMessageDelayed(MSG_NO_MORE_FACES_IN_SCENE,
                    PAUSE_FACE_PROCESSING_POST_FACE_FOCUS_TRIGGER_TIME);
            return;
        }
        if (face != null) {
            if (mLastFocusTriggeredFace == null
                    || mLastFocusTriggeredFace.getId() != face.getId()) {
                LogUtil.d(TAG, "FaceDetector: triggering focus with face: " + face);
                triggerFocusWithFace(face);
                return;
            }
            LogUtil.d(TAG, "Number of faces changed, but the previous face is still in preview, "
                    + "not triggering focus" + face);
        }
    }

    private void triggerFocusWithFace(Face face) {
        mMainLooperHandler.removeMessages(MSG_NO_MORE_FACES_IN_SCENE);
        mLastFocusTriggeredFace = face;
        Rect bounds = face.getBounds();
        mPauseFaceProcessingPostFaceFocusTrigger = true;
        mTimingLoggerUtil.captureTiming(TimingLoggerUtil.TimeToAutoFocusSplits.FACE_DETECTED);
        triggerFocus(FocusTriggerSource.FACE_DETECTION,
                Math.round(bounds.centerX() + FOCUS_TRIGGER_OFFSET),
                Math.round(bounds.centerY()));
        mMainLooperHandler.sendEmptyMessageDelayed(MSG_RESUME_FACE_PROCESSING_FOR_FOCUS,
                PAUSE_FACE_PROCESSING_POST_FACE_FOCUS_TRIGGER_TIME);
    }

    private Face getQualifiedFace(List<Face> faces) {
        if (faces.isEmpty()) {
            return null;
        }
        Face face = faces.get(0);
        if (face.getScore() >= MINIMUM_FACE_SCORE_FOR_FOCUS_TRIGGER) {
            return face;
        }
        return null;
    }

    private void triggerFocus(FocusTriggerSource source, int x, int y) {
        mMostRecentFocusTriggerSource = source;
        mCameraManager.get().triggerAeFocusAtXY(x, y);
    }

    private void triggerFocusAtCenter(FocusTriggerSource source) {
        mMostRecentFocusTriggerSource = source;
        mCameraManager.get().triggerAeFocusAtCenter();
    }

    private void initSignificantMotionDetector() {
        if (!mAfdModeEnabled || mSignificantMotionDetectorRegistered) {
            return;
        }
        LogUtil.d(TAG, "Enable Motion Detector for focus triggers");
        mSignificantMotionDetector.registerListener(this);
        mSignificantMotionDetectorRegistered = true;
    }

    private void releaseSignificantMotionDetector() {
        if (mSignificantMotionDetectorRegistered) {
            LogUtil.d(TAG, "Release Motion Detector for focus triggers");
            mMainLooperHandler.removeMessages(MSG_ENABLE_SIGNIFICATION_MOTION_DETECTION);
            mSignificantMotionDetector.unregisterListener(this);
            mSignificantMotionDetectorRegistered = false;
        }
    }

    @Override
    public void onMotionDetected() {
        mTimingLoggerUtil.captureTiming(
                TimingLoggerUtil.TimeToAutoFocusSplits.SIGNIFICANT_MOTION);
        if (mFtuHelper.isFtuPlaying()) {
            return;
        }
        triggerFocusAtCenter(FocusTriggerSource.SIGNIFICATION_MOTION);
        mNumberOfFacesSeenPreviously = 0;
    }

    public boolean checkAndUpdateAfMode() {
        return "cam_caf_mode_afd".equals(CamPrefsUtils.getCafMode());
    }

    public boolean isAfModeSettingAfd() {
        return mAfdModeEnabled;
    }

    public boolean isScreenBasedFocusLocked() {
        return mMostRecentFocusTriggerSource == FocusTriggerSource.USER_SCREEN_LOCK;
    }

    public AutoFocusMode getCurrentAfMode() {
        return isAfModeSettingAfd() ? AutoFocusMode.AFD : AutoFocusMode.AFS;
    }

    private void resetTriggerSourceAndEnableAfd() {
        mMostRecentFocusTriggerSource = FocusTriggerSource.SYSTEM;
        mPauseFaceProcessingPostFaceFocusTrigger = true;
        mMainLooperHandler.sendEmptyMessageDelayed(MSG_ENABLE_SIGNIFICATION_MOTION_DETECTION,
                PAUSE_MOTION_DETECT_POST_USER_TAP_TIME);
        mMainLooperHandler.sendEmptyMessageDelayed(MSG_RESUME_FACE_PROCESSING_FOR_FOCUS,
                PAUSE_MOTION_DETECT_POST_USER_TAP_TIME);
    }
}