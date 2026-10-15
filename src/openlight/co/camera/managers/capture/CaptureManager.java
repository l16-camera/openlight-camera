package openlight.co.camera.managers.capture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ImageReader;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Pair;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import ltpb.ViewPreferences;
import openlight.co.camera.CameraApp;
import openlight.co.camera.CameraInfo;
import openlight.co.camera.ImageReaderManager;
import openlight.co.camera.managers.CameraManager;
import openlight.co.camera.managers.autoexposure.AutoExposureManager;
import openlight.co.camera.managers.capturerequest.CaptureRequestManager;
import openlight.co.camera.managers.focus.FocusManager;
import openlight.co.camera.managers.focus.SmartAFTriggerMgr;
import openlight.co.camera.managers.zoom.ZoomManager;
import openlight.co.camera.metrics.CameraMetrics;
import openlight.co.camera.metrics.Metrics;
import openlight.co.camera.proto.CameraCaptureRequestInfo;
import openlight.co.camera.utils.CameraState;
import openlight.co.camera.utils.ImageUtil;
import openlight.co.camera.utils.MediaFileManager;
import openlight.co.camera.utils.Provider;
import openlight.co.camera.utils.TimingLoggerUtil;
import openlight.co.camera.utils.Util;
import openlight.co.lib.content.CamPrefsFactory;
import openlight.co.lib.content.Prefs;
import openlight.co.lib.exif.ExifInformation;
import openlight.co.lib.utils.LogUtil;

public class CaptureManager {
    private static final String TAG = "CaptureManager";
    private static final CaptureManager sInstance = new CaptureManager();

    private final CaptureRequestManager mCamReqManager = CaptureRequestManager.get();
    private final Prefs mCamPref = CamPrefsFactory.get();
    private final CameraInfo mCamInfo = CameraInfo.get();
    private final CaptureBurstManager mCaptureBurst = CaptureBurstManager.get();
    private final CaptureRequestManager mCaptureReqManager = CaptureRequestManager.get();
    private final CameraState mCameraState = CameraState.get();
    private final Metrics mMetrics = Metrics.get();
    private final SmartAFTriggerMgr mSmartAfTriggerMgr = SmartAFTriggerMgr.get();
    private final Provider<CameraManager> mCameraManager = CameraManager::get;
    private final Provider<AutoExposureManager> mAutoExposureManager = AutoExposureManager::get;
    private final Provider<FocusManager> mFocusManager = FocusManager::get;
    private final ImageReaderManager mImageReaderManager = ImageReaderManager.get();
    private final TimingLoggerUtil mTimingLoggerUtil = TimingLoggerUtil.get();
    private final ZoomManager mZoomManager = ZoomManager.get();

    private CameraCaptureRequestInfo mCaptureInfo;
    private CaptureUiUpdate mCaptureUpdateListener;
    private int mPendingUserCaptures = 0;
    private boolean mNativeAspectRatio = true;
    private volatile State mCurrentState = State.IDLE;
    /** Self-timer: when it runs out (uptime ms) and how long it was; 0 when none runs. */
    private volatile long mTimerEndMs;
    private volatile long mTimerMs;
    private boolean mIsBurstCapture = false;
    private final AtomicInteger mRequestCounter = new AtomicInteger();

    private final CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    ImageUtil.ImageSaver.ImageSaverBuilder jpegImageBuilder;
                    ImageUtil.ImageSaver.ImageSaverBuilder rawImageBuilder;
                    LogUtil.d(TAG, "onCaptureStarted : frameNumber " + frameNumber
                            + " timestamp " + timestamp);
                    if (request.getTag() == null) {
                        LogUtil.w(TAG, "onCaptureStart, request tag is null");
                        return;
                    }
                    MediaFileManager mediaFileManager = MediaFileManager.get();
                    String imagePath = mediaFileManager.getImagePath();
                    if (TextUtils.isEmpty(imagePath)) {
                        LogUtil.w(TAG, "onCaptureStarted, folder to create jpeg/raw invalid "
                                + imagePath);
                        return;
                    }
                    Pair<File, File> nextProcessedAndRawFileNames =
                            mediaFileManager.getNextProcessedAndRawFileNames();
                    File jpegFile = nextProcessedAndRawFileNames.first;
                    LogUtil.d(TAG, "onCaptureStarted:: JPEG File Name: " + jpegFile);
                    File rawFile = nextProcessedAndRawFileNames.second;
                    LogUtil.d(TAG, "onCaptureStarted:: Raw File Name: " + rawFile);
                    setViewPrefHeader(request);
                    int tag = ((Integer) request.getTag()).intValue();
                    synchronized (mCameraManager.get().getCameraStateLock()) {
                        jpegImageBuilder = mImageReaderManager.getJpegImageBuilder(tag);
                        rawImageBuilder = mImageReaderManager.getRawImageBuilder(tag);
                    }
                    ViewPreferences viewPrefs = ImageUtil.getViewPrefs(mCaptureInfo);
                    if (jpegImageBuilder != null) {
                        jpegImageBuilder.setFile(jpegFile);
                        jpegImageBuilder.setViewPrefs(viewPrefs);
                        jpegImageBuilder.setExifInfo(createExifData());
                    }
                    if (rawImageBuilder != null) {
                        rawImageBuilder.setFile(rawFile);
                        rawImageBuilder.setViewPrefs(viewPrefs);
                    }
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    LogUtil.i(TAG, "onCaptureCompleted : SequenceId " + result.getSequenceId()
                            + " frameNumber " + result.getFrameNumber());
                    mTimingLoggerUtil.captureTiming(
                            TimingLoggerUtil.TimeToCaptureSplits.CAPTURE_COMPLETE);
                    if (request.getTag() == null) {
                        LogUtil.w(TAG, "onCaptureCompleted, request tag is null");
                        return;
                    }
                    int tag = ((Integer) request.getTag()).intValue();
                    if (mCaptureUpdateListener != null) {
                        mCaptureUpdateListener.onCaptureComplete();
                    }
                    CameraManager cameraManager = mCameraManager.get();
                    synchronized (cameraManager.getCameraStateLock()) {
                        mImageReaderManager.handleCompletionLocked(tag,
                                mImageReaderManager.getJpegImageBuilder(tag),
                                mImageReaderManager.getJpegResultQueue());
                        ImageUtil.ImageSaver.ImageSaverBuilder rawImageBuilder =
                                mImageReaderManager.getRawImageBuilder(tag);
                        if (rawImageBuilder != null) {
                            mImageReaderManager.handleCompletionLocked(tag, rawImageBuilder,
                                    mImageReaderManager.getRawResultQueue());
                        }
                        cameraManager.captureComplete();
                    }
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    int tag = ((Integer) request.getTag()).intValue();
                    LogUtil.e(TAG, "Capture Failed : Reason: " + failure.getReason()
                            + " Sequence Id " + failure.getSequenceId()
                            + " frameNumber " + failure.getFrameNumber());
                    CameraManager cameraManager = mCameraManager.get();
                    cameraManager.decrementCapturesInFlight();
                    LogUtil.d(TAG, "onCaptureFailed image: "
                            + cameraManager.getCapturesInFlight());
                    synchronized (cameraManager.getCameraStateLock()) {
                        mImageReaderManager.removeJpegRequestQueue(tag);
                        mImageReaderManager.removeRawRequestQueue(tag);
                        cameraManager.captureComplete();
                    }
                    if (mCaptureUpdateListener != null) {
                        mCaptureUpdateListener.onCaptureFailure();
                    }
                }
            };

    public interface CaptureUiUpdate {
        void onCaptureComplete();
        void onCaptureFailure();
        void onStartAnimationForBurstCapture();
        void onStartAnimationForSingleCapture();
        void onStartTimerForCapture(String timerSetting);
        void onUpdateCaptureImageVisiblity(int visibility);
        void onUpdateUiForCapture();
    }

    public enum State {
        IDLE,
        QUEUED_CAPTURE,
        PRE_CAPTURE_PROCESSING,
        CAPTURING
    }

    public static CaptureManager get() {
        return sInstance;
    }

    private CaptureManager() {
    }

    public void doCapture() {
        boolean isCurrentlyZooming = mZoomManager.isCurrentlyZooming();
        boolean isFocusing = mFocusManager.get().isFocusing();
        boolean isMetering = mAutoExposureManager.get().isMetering();
        LogUtil.d(TAG, "[CAPTURE] doCapture: Capture State: " + mCurrentState
                + "is metering: " + isMetering + "is focusing: " + isFocusing
                + "is zooming: " + isCurrentlyZooming);
        if (mCurrentState == State.IDLE && (isFocusing || isMetering)) {
            mCurrentState = State.QUEUED_CAPTURE;
            LogUtil.w(TAG, "Focus in progress. Queueing Capture");
            return;
        }
        if (mCurrentState == State.PRE_CAPTURE_PROCESSING || mCurrentState == State.CAPTURING
                || isCurrentlyZooming) {
            LogUtil.w(TAG, "Ignoring Capture Request. Processing a capture request, "
                    + "or in capture, or currently zooming");
            return;
        }
        if (mCurrentState == State.QUEUED_CAPTURE && isFocusing) {
            LogUtil.w(TAG, "Capture queue already has a request, ignoring further queuing "
                    + "request. Currently Focusing");
            return;
        }
        if (!Util.isAvailableSpace()) {
            Util.showToastForLowMemory(CameraApp.get());
            return;
        }
        mCurrentState = State.PRE_CAPTURE_PROCESSING;
        if (mCaptureUpdateListener != null) {
            mCaptureUpdateListener.onUpdateUiForCapture();
        }
        String timerSetting = mCamPref.getStringValue("timer_setting");
        if ("timer_off".equals(timerSetting)) {
            takePicture();
        } else if (mCaptureUpdateListener != null) {
            mTimerMs = timerMs(timerSetting);
            mTimerEndMs = SystemClock.uptimeMillis() + mTimerMs;
            mCaptureUpdateListener.onStartTimerForCapture(timerSetting);
        }
    }

    /** "3s" → 3000. */
    private static long timerMs(String setting) {
        try {
            return 1000L * Integer.parseInt(setting.replace("s", "").trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Self-timer time left, 0 when no countdown runs. For the astro shutter light. */
    public long getTimerRemainingMs() {
        long end = mTimerEndMs;
        if (end == 0 || mCurrentState != State.PRE_CAPTURE_PROCESSING) {
            return 0;
        }
        return Math.max(0, end - SystemClock.uptimeMillis());
    }

    public long getTimerTotalMs() {
        return mTimerMs;
    }

    public void takePictureOnTimerComplete() {
        takePicture();
    }

    private void takePicture() {
        mTimerEndMs = 0;
        CameraManager cameraManager = mCameraManager.get();
        synchronized (cameraManager.getCameraStateLock()) {
            if (mCameraState.getSessionState() != CameraState.SessionState.PREVIEW) {
                LogUtil.w(TAG, "Current session is not allowing Capture. Resetting capture request");
                if (mCaptureUpdateListener != null) {
                    mCaptureUpdateListener.onUpdateCaptureImageVisiblity(8);
                    mCaptureUpdateListener.onCaptureComplete();
                }
                mCurrentState = State.IDLE;
                return;
            }
            mPendingUserCaptures = getTotalCaptureForTypes();
            LogUtil.d(TAG, "Get pending user Capture:  " + mPendingUserCaptures);
            try {
                mCaptureReqManager.startCapture(cameraManager.getPreviewRequestBuilder());
                isBurstOrStillCapture();
            } catch (Exception e) {
                LogUtil.e(TAG, "Exception in takePicture", e);
                cameraManager.decrementCapturesInFlight();
                mPendingUserCaptures--;
                LogUtil.d(TAG, "Capture In Flight count: " + cameraManager.getCapturesInFlight());
            }
        }
    }

    private void isBurstOrStillCapture() {
        mIsBurstCapture = mCameraState.getStillCaptureMode()
                .equals(CameraState.StillCaptureMode.BURST);
    }

    public int getTotalCaptureForTypes() {
        LogUtil.d(TAG, "Burst State: " + mCameraState.getStillCaptureMode());
        if (mCameraState.getStillCaptureMode().equals(CameraState.StillCaptureMode.BURST)) {
            return mCaptureBurst.getPendingUserCaptureCount();
        }
        return 1;
    }

    public void resetCapture() {
        mTimerEndMs = 0;
        mCurrentState = State.IDLE;
    }

    public void captureStillPictureLocked() {
        if (mCurrentState == State.CAPTURING) {
            LogUtil.i(TAG, "Capturing has starting. Ignoring further request.");
            return;
        }
        mCurrentState = State.CAPTURING;
        CameraManager cameraManager = mCameraManager.get();
        try {
            CaptureRequest.Builder builder =
                    cameraManager.getCameraDevice().createCaptureRequest(2);
            builder.addTarget(mImageReaderManager.getJpegImageReader().getSurface());
            ImageReader rawImageReader = mImageReaderManager.getRawImageReader();
            if (rawImageReader != null) {
                builder.addTarget(rawImageReader.getSurface());
            }
            mCamReqManager.createStillCaptureRequest(builder);
            builder.setTag(mRequestCounter.getAndIncrement());
            CaptureRequest request = builder.build();
            int tag = ((Integer) request.getTag()).intValue();
            mImageReaderManager.addJpegResultQueue(tag,
                    new ImageUtil.ImageSaver.ImageSaverBuilder(CameraApp.get()));
            if (CameraApp.isLight()) {
                mImageReaderManager.addRawResultQueue(tag,
                        new ImageUtil.ImageSaver.ImageSaverBuilder(CameraApp.get()));
            }
            mNativeAspectRatio = "4:3".equals(mCamPref.getStringValue("aspect_ratio_setting"));
            LogUtil.i(TAG, "Issue Capture Request");
            cameraManager.incrementCapturesInFlight();
            int sequenceId = cameraManager.getCameraCaptureSession().capture(
                    request, mCaptureCallback, cameraManager.getCameraBackgroundHandler());
            decrementPendingUserCapture();
            mTimingLoggerUtil.captureTiming(
                    TimingLoggerUtil.TimeToCaptureSplits.ISSUE_CAPTURE_REQUEST_TO_PLATFORM);
            if (mCaptureUpdateListener != null) {
                mCaptureUpdateListener.onStartAnimationForSingleCapture();
            }
            LogUtil.i(TAG, "Capture Request submitted, sequenceId " + sequenceId
                    + " and Number of captures in flight: "
                    + cameraManager.getCapturesInFlight());
            mMetrics.add(CameraMetrics.EVENT_CAPTURE_SINGLE,
                    CameraMetrics.createPropertiesForSingleCapture(
                            cameraManager.getCameraMode().toString(),
                            mSmartAfTriggerMgr.getCurrentAfMode(),
                            mZoomManager.getZoomLevel()));
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            LogUtil.e(TAG, "Exception in captureStillPictureLocked", e);
            cameraManager.decrementCapturesInFlight();
            resetCaptureParamsOnException();
        }
    }

    public void captureBurstPictureLocked(int burstIndex) {
        CameraManager cameraManager = mCameraManager.get();
        try {
            CaptureRequest.Builder builder =
                    cameraManager.getCameraDevice().createCaptureRequest(2);
            builder.addTarget(mImageReaderManager.getJpegImageReader().getSurface());
            ImageReader rawImageReader = mImageReaderManager.getRawImageReader();
            if (rawImageReader != null) {
                builder.addTarget(rawImageReader.getSurface());
            }
            mCaptureBurst.setCaptureRequestForBurst(builder, burstIndex);
            LogUtil.d(TAG, "Burst fetch request: " + mCaptureBurst.getIfFetchRequest());
            mCamReqManager.createBurstCaptureRequest(builder);
            builder.setTag(mRequestCounter.getAndIncrement());
            CaptureRequest request = builder.build();
            int tag = ((Integer) request.getTag()).intValue();
            mImageReaderManager.addJpegResultQueue(tag,
                    new ImageUtil.ImageSaver.ImageSaverBuilder(CameraApp.get()));
            if (CameraApp.isLight()) {
                mImageReaderManager.addRawResultQueue(tag,
                        new ImageUtil.ImageSaver.ImageSaverBuilder(CameraApp.get()));
            }
            mNativeAspectRatio = "4:3".equals(mCamPref.getStringValue("aspect_ratio_setting"));
            LogUtil.d(TAG, "Issue burst Capture ");
            cameraManager.incrementCapturesInFlight();
            int sequenceId = cameraManager.getCameraCaptureSession().capture(
                    request, mCaptureCallback, cameraManager.getCameraBackgroundHandler());
            LogUtil.i(TAG, "Burst Capture Request submitted, sequenceId " + sequenceId);
            if (!mCaptureBurst.getIfFetchRequest() && mCaptureUpdateListener != null) {
                mCaptureUpdateListener.onStartAnimationForBurstCapture();
            }
            decrementPendingUserCapture();
            if (getPendingUserCaptures() == 0) {
                mMetrics.add(CameraMetrics.EVENT_CAPTURE_BURST,
                        CameraMetrics.createPropertiesForBurstCapture(
                                mCaptureBurst.getPendingUserCaptureCount(),
                                cameraManager.getCameraMode().toString(),
                                mSmartAfTriggerMgr.getCurrentAfMode(),
                                mZoomManager.getZoomLevel()));
            }
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            LogUtil.e(TAG, "Exception in captureBurstPictureLocked", e);
            cameraManager.decrementCapturesInFlight();
            resetCaptureParamsOnException();
        }
    }

    private void decrementPendingUserCapture() {
        if (mPendingUserCaptures > 0) {
            mPendingUserCaptures--;
        }
    }

    public void resetPendingUserCaptures() {
        mPendingUserCaptures = 0;
    }

    public void resetRequestCounterForCapture() {
        mRequestCounter.set(0);
    }

    public void finishedCaptureLocked() {
        try {
            mCurrentState = State.IDLE;
            CameraManager cameraManager = mCameraManager.get();
            mCaptureReqManager.resetPostCapture(cameraManager.getPreviewRequestBuilder());
            cameraManager.startRepeatingRequestInPreview();
            LogUtil.i(TAG, "Capture Completed. Resetting to Preview");
        } catch (IllegalArgumentException | IllegalStateException e) {
            LogUtil.e(TAG, "Exception in finishCaptureLocked", e);
        }
    }

    public int getPendingUserCaptures() {
        return mPendingUserCaptures;
    }

    public void setUiUpdateListener(CaptureUiUpdate listener) {
        mCaptureUpdateListener = listener;
    }

    public boolean isBurstCapture() {
        return mIsBurstCapture;
    }

    public boolean isIdle() {
        return mCurrentState == State.IDLE;
    }

    public boolean isQueued() {
        return mCurrentState == State.QUEUED_CAPTURE;
    }

    private void setViewPrefHeader(CaptureRequest request) {
        if (request == null) {
            return;
        }
        Integer awbMode = (Integer) request.get(CaptureRequest.CONTROL_AWB_MODE);
        Integer exposureComp = (Integer) request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
        if (awbMode == null || exposureComp == null) {
            return;
        }
        mCaptureInfo = new CameraCaptureRequestInfo(getComputedExposureComp(exposureComp.intValue()));
        mCaptureInfo.setAspectRatio(mNativeAspectRatio);
        mCaptureInfo.setOrientationAngle(
                ((Integer) request.get(CaptureRequest.JPEG_ORIENTATION)).intValue());
        mCaptureInfo.setAwbMode(awbMode.intValue());
    }

    private ExifInformation createExifData() {
        return new ExifInformation(mCaptureInfo.getEvOffset(),
                (int) mCamPref.getFloatValue("zoom_value"));
    }

    public boolean isPendingCapturePostFocus() {
        return mCurrentState == State.QUEUED_CAPTURE;
    }

    public boolean isCapturing() {
        return mCurrentState == State.CAPTURING;
    }

    private float getComputedExposureComp(int exposureComp) {
        return exposureComp * mCamInfo.getSupportedAeStep().floatValue();
    }

    private void resetCaptureParamsOnException() {
        mRequestCounter.decrementAndGet();
        if (mCaptureUpdateListener != null) {
            mCaptureUpdateListener.onUpdateCaptureImageVisiblity(8);
            mCaptureUpdateListener.onCaptureComplete();
        }
        mCurrentState = State.IDLE;
    }

    public State getCurrentState() {
        return mCurrentState;
    }
}