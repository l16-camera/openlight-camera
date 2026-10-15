package openlight.co.camera.managers.focus;

import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.Surface;
import openlight.co.camera.listener.FocusListener;
import openlight.co.camera.managers.CameraManager;
import openlight.co.camera.managers.capturerequest.CaptureRequestBuilder;
import openlight.co.camera.managers.capturerequest.CaptureRequestManager;
import openlight.co.camera.models.MeteringPoint;
import openlight.co.camera.utils.CameraState;
import openlight.co.camera.utils.MeteringRect;
import openlight.co.camera.utils.Provider;
import openlight.co.camera.utils.TimingLoggerUtil;
import openlight.co.lib.content.CamPrefsFactory;
import openlight.co.lib.content.Prefs;
import openlight.co.lib.utils.LogUtil;

public class FocusManager {
    private static final String TAG = "FocusManager";
    private static final FocusManager sInstance = new FocusManager();
    private static final SmartAFTriggerMgr mSmartAfTriggerMgr = SmartAFTriggerMgr.get();

    private final Provider<CameraManager> mCameraManager = CameraManager::get;
    private final CameraState mCameraState = CameraState.get();
    private final TimingLoggerUtil mTimingLoggerUtil = TimingLoggerUtil.get();
    private final Prefs mCamPref = CamPrefsFactory.get();
    private final MeteringPoint mFocusPoint = new MeteringPoint();
    private MeteringRectangle mFocusRoi = new MeteringRectangle(new Rect(), 1000);
    private long mFocusStartTime = 0;
    private volatile State mCurrentState = State.IDLE;
    private boolean mCurrentTorchState = false;
    private FocusListener mFocusListener;

    private final CameraCaptureSession.CaptureCallback mFocusCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                }

                private void process(@NonNull CaptureResult result) {
                    LogUtil.d(TAG, "[AF] Focus Callback state: "
                            + result.get(CaptureResult.CONTROL_AF_STATE)
                            + " in frame: " + result.getFrameNumber());
                    if (mFocusListener != null) {
                        mFocusListener.focusCompleted();
                    } else {
                        LogUtil.v(TAG, "Focus Completed: Focus Listener is null");
                    }
                }

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    if (mCurrentState == State.IDLE) {
                        LogUtil.w(TAG, "Ignoring onCaptureStarted, current state is IDLE, "
                                + "frame number: " + frameNumber);
                        return;
                    }
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                    mCameraManager.get().focusTriggered();
                    mFocusStartTime = SystemClock.elapsedRealtime();
                    if (mFocusListener != null) {
                        mFocusListener.focusStarted();
                    } else {
                        LogUtil.v(TAG, "Focus Started: Focus Listener is null");
                    }
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    if (mCurrentState != State.IDLE) {
                        LogUtil.d(TAG, "FOCUS COMPLETED: " + result.getFrameNumber());
                        process(result);
                        return;
                    }
                    LogUtil.w(TAG, "Ignoring onCaptureComplete, current state is IDLE, "
                            + "frame number: " + result.getFrameNumber());
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    super.onCaptureFailed(session, request, failure);
                    if (mCurrentState == State.IDLE) {
                        LogUtil.w(TAG, "Ignoring onCaptureFailed, current state is IDLE, "
                                + "frame number: " + failure.getFrameNumber());
                        return;
                    }
                    mCurrentState = State.IDLE;
                    LogUtil.d(TAG, "FOCUS FAILED: " + failure.getFrameNumber());
                    if (mFocusListener != null) {
                        mFocusListener.focusFailed();
                    } else {
                        LogUtil.v(TAG, "Focus Failed, Focus listener is null");
                    }
                    mSmartAfTriggerMgr.focusFailed();
                    mCameraManager.get().focusComplete();
                }
            };

    public enum State {
        IDLE,
        FOCUSING_FIRST_TIME,
        FOCUSING,
        LOCKED_FIRST_TIME,
        LOCKED
    }

    public static FocusManager get() {
        return sInstance;
    }

    private FocusManager() {
    }

    public void setFocusListener(FocusListener focusListener) {
        mFocusListener = focusListener;
    }

    public void cancelAfTrigger(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, 0);
        CameraManager cameraManager = CameraManager.get();
        try {
            cameraManager.getCameraCaptureSession().capture(
                    builder.build(), mFocusCallback, cameraManager.getCameraBackgroundHandler());
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public void autoTriggerFocus() {
        mCurrentState = State.FOCUSING;
        CameraManager cameraManager = mCameraManager.get();
        if (cameraManager.isCameraInOpenState()) {
            Surface surface = mCameraState.getSurface();
            Pair meteringRectAndScreenPoint = MeteringRect.get().getMeteringRectAndScreenPoint(
                    mFocusPoint, mCameraState.getAutoFitTextureView(), false);
            try {
                mFocusRoi = (MeteringRectangle) meteringRectAndScreenPoint.first;
                mFocusPoint.set((Point) meteringRectAndScreenPoint.second);
                CaptureRequest.Builder createCaptureRequest =
                        cameraManager.getCameraDevice().createCaptureRequest(1);
                createCaptureRequest.addTarget(surface);
                CaptureRequestManager.get().setFocusCaptureRequest(createCaptureRequest);
                createCaptureRequest.set(CaptureRequest.CONTROL_AF_REGIONS,
                        new MeteringRectangle[] {mFocusRoi});
                createCaptureRequest.set(CaptureRequest.CONTROL_AE_REGIONS, null);
                createCaptureRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, null);
                createCaptureRequest.set(CaptureRequest.SCALER_CROP_REGION, null);
                // Through the helper, not straight onto the builder: this request targets the
                // preview surface, and setting the key directly skipped monochrome mode's snap
                // and grey effect — every focus frame reached the viewfinder in colour.
                CaptureRequestBuilder.setLensFocalLength(createCaptureRequest, getFocalLength());
                if (getTorchState()) {
                    createCaptureRequest.set(CaptureRequest.FLASH_MODE, 2);
                }
                createCaptureRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, 1);
                createCaptureRequest.set(CaptureRequest.TONEMAP_MODE, 2);
                LogUtil.d(TAG, "Issuing Focus Request to Platform");
                mTimingLoggerUtil.captureTiming(
                        TimingLoggerUtil.TimeToAutoFocusSplits.ISSUE_FOCUS_REQUEST_TO_PLATFORM);
                cameraManager.getCameraCaptureSession().capture(
                        createCaptureRequest.build(), mFocusCallback,
                        cameraManager.getCameraBackgroundHandler());
                return;
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                LogUtil.e(TAG, "Exception when triggering focus", e);
                return;
            }
        }
        LogUtil.w(TAG,
                "The camera device is either still not open or is closing, ignore focus request");
    }

    public void setTorchForFlash(CaptureRequest.Builder builder, boolean enableTorch) {
        mCurrentTorchState = enableTorch;
        int flashMode = enableTorch ? 2 : 0;
        LogUtil.d(TAG, "Enable/Disable Torch");
        CaptureRequestBuilder.setFlashMode(builder, flashMode);
        mCameraManager.get().startCaptureRequest();
    }

    public boolean hasInitialFocusCompleted() {
        return mCurrentState == State.LOCKED;
    }

    public void unregisterFocusListener() {
        setFocusListener(null);
    }

    public void resetFocusStateToIdle() {
        mCurrentState = State.IDLE;
        mFocusPoint.resetToCenter();
    }

    public MeteringRectangle getFocusRoi() {
        return mFocusRoi;
    }

    public long getFocusStartTime() {
        return mFocusStartTime;
    }

    public void setFocusPointToCenter() {
        mFocusPoint.resetToCenter();
    }

    public void updateFocusPoint(MotionEvent event) {
        mFocusPoint.set((int) event.getX(), (int) event.getY());
    }

    public void updateFocusPoint(int x, int y) {
        mFocusPoint.set(x, y);
    }

    public boolean isFocusing() {
        return mCurrentState == State.FOCUSING;
    }

    public void setFocusCompleted() {
        mCurrentState = State.LOCKED;
        mSmartAfTriggerMgr.focusComplete();
    }

    public boolean isIdle() {
        return mCurrentState == State.IDLE;
    }

    public void startFirstTimeFocus() {
        mCurrentState = State.FOCUSING_FIRST_TIME;
    }

    public boolean isFocusingFirstTime() {
        return mCurrentState == State.FOCUSING_FIRST_TIME;
    }

    public State getCurrentState() {
        return mCurrentState;
    }

    private boolean getTorchState() {
        return mCurrentTorchState;
    }

    public MeteringPoint getCurrentFocusPoint() {
        return mFocusPoint;
    }

    private float getFocalLength() {
        return mCamPref.getFloatValue("focal_length");
    }
}