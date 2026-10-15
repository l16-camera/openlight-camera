package openlight.co.camera;

import openlight.co.camera.astro.AstroAnalyzer;
import android.graphics.ImageFormat;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import openlight.co.camera.listener.OnImageSavedListener;
import openlight.co.camera.managers.mono.MonoMode;
import openlight.co.camera.managers.mono.MonoProxy;
import openlight.co.camera.managers.CameraManager;
import openlight.co.camera.utils.CamPrefsUtils;
import openlight.co.camera.utils.ExecutorUtil;
import openlight.co.camera.utils.FaceDetector;
import openlight.co.camera.utils.ImageUtil;
import openlight.co.camera.utils.TimingLoggerUtil;
import openlight.co.lib.content.CamPrefsFactory;
import openlight.co.lib.content.Prefs;
import openlight.co.lib.utils.LogUtil;
import openlight.co.lib.utils.Utils;

public class ImageReaderManager {
    private static final int IMAGE_READER_BUFFER_COUNT = 5;
    private static final int IMAGE_READER_BUFFER_COUNT_HISTOGRAM = 30;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_WIDTH_4_3 = 1440;
    private static final String TAG = Utils.safeTag(ImageReaderManager.class);

    private static ImageReaderManager sInstance;

    private final CameraInfo mCameraInfo = CameraInfo.get();
    private final Prefs mCameraPref = CamPrefsFactory.get();
    private final TimingLoggerUtil mTimingLoggerUtil = TimingLoggerUtil.get();
    private final TreeMap<Integer, ImageUtil.ImageSaver.ImageSaverBuilder> mJpegResultQueue =
            new TreeMap<>();
    private final TreeMap<Integer, ImageUtil.ImageSaver.ImageSaverBuilder> mRawResultQueue =
            new TreeMap<>();

    private RefCountedAutoCloseable<ImageReader> mJpegImageReader;
    private RefCountedAutoCloseable<ImageReader> mRawImageReader;
    private RefCountedAutoCloseable<ImageReader> mYuvImageReader;
    private OnImageStatusListener mOnImageStatusListener;
    private PendingCapturesCompleteListener mPendingCapturesCompleteListener;

    private final ImageReader.OnImageAvailableListener mOnJpegImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    LogUtil.i(TAG, "JPEG Image Available");
                    mTimingLoggerUtil.captureTiming(
                            TimingLoggerUtil.TimeToCaptureSplits.JPEG_AVAILABLE);
                    dequeueAndSaveImage(mJpegResultQueue, mJpegImageReader);
                }
            };

    private final ImageReader.OnImageAvailableListener mOnYuvImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    if (mOnImageStatusListener != null) {
                        mOnImageStatusListener.onYuvImageAvailable(reader);
                    }
                }
            };

    private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    LogUtil.i(TAG, "RAW Image Available");
                    mTimingLoggerUtil.captureTiming(
                            TimingLoggerUtil.TimeToCaptureSplits.RAW_AVAILABLE);
                    dequeueAndSaveImage(mRawResultQueue, mRawImageReader);
                }
            };

    public interface OnImageStatusListener {
        void onImageSaved(int format, String path, int orientation);
        void onYuvImageAvailable(ImageReader reader);
    }

    public interface PendingCapturesCompleteListener {
        void onComplete();
    }

    public static synchronized ImageReaderManager get() {
        if (sInstance == null) {
            sInstance = new ImageReaderManager();
        }
        return sInstance;
    }

    public void setUpImageReaders() {
        synchronized (CameraManager.get().getCameraStateLock()) {
            Size largestRawOutputSize = mCameraInfo.getLargestRawOutputSize();
            Size jpegAndYuvSize = getJpegAndYuvSize();
            int rawFormat = mCameraInfo.getRawFormat();
            Handler cameraBackgroundHandler = CameraManager.get().getCameraBackgroundHandler();

            if (mJpegImageReader == null || mJpegImageReader.get() == null) {
                mJpegImageReader = new RefCountedAutoCloseable<>(ImageReader.newInstance(
                        jpegAndYuvSize.getWidth(), jpegAndYuvSize.getHeight(),
                        ImageFormat.JPEG, IMAGE_READER_BUFFER_COUNT));
            }
            mJpegImageReader.get().setOnImageAvailableListener(
                    mOnJpegImageAvailableListener, cameraBackgroundHandler);
            LogUtil.d(TAG, "JPEG Image Reader: " + mJpegImageReader);

            if (CameraApp.isLight()) {
                if (mRawImageReader == null || mRawImageReader.get() == null) {
                    mRawImageReader = new RefCountedAutoCloseable<>(ImageReader.newInstance(
                            largestRawOutputSize.getWidth(), largestRawOutputSize.getHeight(),
                            rawFormat, IMAGE_READER_BUFFER_COUNT));
                }
                mRawImageReader.get().setOnImageAvailableListener(
                        mOnRawImageAvailableListener, cameraBackgroundHandler);
                LogUtil.d(TAG, "RAW Image Reader: " + mRawImageReader);
            }

            if (CameraActivity.HISTOGRAM_SUPPORTED
                    && !FaceDetector.get().isFaceDetectionFeatureEnabled()) {
                if (mYuvImageReader == null || mYuvImageReader.get() == null) {
                    mYuvImageReader = new RefCountedAutoCloseable<>(ImageReader.newInstance(
                            jpegAndYuvSize.getWidth(), jpegAndYuvSize.getHeight(),
                            ImageFormat.YUV_420_888, IMAGE_READER_BUFFER_COUNT_HISTOGRAM));
                }
                mYuvImageReader.get().setOnImageAvailableListener(
                        mOnYuvImageAvailableListener, cameraBackgroundHandler);
            }
            LogUtil.d(TAG, "YUV Image Reader: " + mYuvImageReader);
        }
    }

    public void closeImageReaders() {
        if (mRawImageReader != null) {
            mRawImageReader.close();
            mRawImageReader = null;
        }
        if (mJpegImageReader != null) {
            mJpegImageReader.close();
            mJpegImageReader = null;
        }
        if (mYuvImageReader != null) {
            mYuvImageReader.close();
            mYuvImageReader = null;
        }
    }

    public void addJpegResultQueue(int tag,
            ImageUtil.ImageSaver.ImageSaverBuilder imageSaverBuilder) {
        mJpegResultQueue.put(tag, imageSaverBuilder);
    }

    public void addRawResultQueue(int tag,
            ImageUtil.ImageSaver.ImageSaverBuilder imageSaverBuilder) {
        mRawResultQueue.put(tag, imageSaverBuilder);
    }

    public void removeJpegRequestQueue(int tag) {
        mJpegResultQueue.remove(tag);
    }

    public void removeRawRequestQueue(int tag) {
        mRawResultQueue.remove(tag);
    }

    public void setOnImageStatusListener(OnImageStatusListener listener) {
        mOnImageStatusListener = listener;
    }

    public void handleCompletionLocked(int tag,
            ImageUtil.ImageSaver.ImageSaverBuilder imageSaverBuilder,
            TreeMap<Integer, ImageUtil.ImageSaver.ImageSaverBuilder> resultQueue) {
        if (imageSaverBuilder == null) {
            return;
        }
        ImageUtil.ImageSaver imageSaver = imageSaverBuilder.buildIfComplete();
        if (imageSaver == null) {
            return;
        }
        resultQueue.remove(tag);
        ExecutorUtil.execute(imageSaver, imageSaver.getName());
    }

    public static class RefCountedAutoCloseable<T extends AutoCloseable> implements AutoCloseable {
        private T mObject;
        int mRefCount = 0;
        private final AtomicInteger mSequence = new AtomicInteger();

        RefCountedAutoCloseable(T object) {
            if (object == null) {
                throw new NullPointerException();
            }
            mObject = object;
        }

        synchronized T getAndRetain() {
            if (mRefCount < 0) {
                return null;
            }
            mRefCount++;
            return mObject;
        }

        synchronized T get() {
            return mObject;
        }

        int getSequence() {
            return mSequence.get();
        }

        int getAndIncrementSequence() {
            return mSequence.getAndIncrement();
        }

        void setSequence(int sequence) {
            mSequence.set(sequence);
        }

        @Override
        public synchronized void close() {
            if (mRefCount >= 0) {
                mRefCount--;
                if (mRefCount < 0) {
                    try {
                        mObject.close();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        mObject = null;
                    }
                }
            }
        }
    }

    public ImageReader getJpegImageReader() {
        return mJpegImageReader.get();
    }

    public ImageReader getRawImageReader() {
        if (mRawImageReader == null) {
            return null;
        }
        return mRawImageReader.get();
    }

    public ImageReader getYuvImageReader() {
        if (mYuvImageReader == null) {
            return null;
        }
        return mYuvImageReader.get();
    }

    public int getJpegRefCount() {
        return mJpegImageReader.mRefCount;
    }

    public int getRawRefCount() {
        if (mRawImageReader == null) {
            return 0;
        }
        return mRawImageReader.mRefCount;
    }

    public TreeMap<Integer, ImageUtil.ImageSaver.ImageSaverBuilder> getJpegResultQueue() {
        return mJpegResultQueue;
    }

    public TreeMap<Integer, ImageUtil.ImageSaver.ImageSaverBuilder> getRawResultQueue() {
        return mRawResultQueue;
    }

    public ImageUtil.ImageSaver.ImageSaverBuilder getJpegImageBuilder(int tag) {
        return mJpegResultQueue.get(tag);
    }

    public ImageUtil.ImageSaver.ImageSaverBuilder getRawImageBuilder(int tag) {
        return mRawResultQueue.get(tag);
    }

    public void setYuvListenerForHistogram(boolean enable, CaptureRequest.Builder builder) {
        if (!CameraActivity.HISTOGRAM_SUPPORTED
                || FaceDetector.get().isFaceDetectionFeatureEnabled()) {
            return;
        }
        Surface surface = mYuvImageReader.get().getSurface();
        if (enable) {
            builder.addTarget(surface);
            mYuvImageReader.get().setOnImageAvailableListener(
                    mOnYuvImageAvailableListener, CameraManager.get().getCameraBackgroundHandler());
        } else {
            builder.removeTarget(surface);
            mYuvImageReader.get().setOnImageAvailableListener(null, null);
        }
    }

    public void setPendingCapturesCompleteListener(PendingCapturesCompleteListener listener) {
        mPendingCapturesCompleteListener = listener;
    }

    private void dequeueAndSaveImage(
            TreeMap<Integer, ImageUtil.ImageSaver.ImageSaverBuilder> resultQueue,
            final RefCountedAutoCloseable<ImageReader> reader) {
        synchronized (CameraManager.get().getCameraStateLock()) {
            Map.Entry<Integer, ImageUtil.ImageSaver.ImageSaverBuilder> entry =
                    resultQueue.firstEntry();
            LogUtil.d(TAG, "[IMAGE] ID: " + reader.getSequence());
            ImageUtil.ImageSaver.ImageSaverBuilder builder = entry.getValue();
            if (reader.getAndRetain() == null) {
                LogUtil.w(TAG, "[IMAGE] Paused the activity before we could save the image, "
                        + "ImageReader already closed.");
                reader.close();
                resultQueue.remove(entry.getKey());
                return;
            }
            try {
                Image image = reader.get().acquireNextImage();
                final int format = image.getFormat();
                LogUtil.d(TAG, "[IMAGE] Light Image format in acquireNextImage reader: " + format);
                builder.setImage(image);
                builder.setSaveImageListener(new OnImageSavedListener() {
                    @Override
                    public void onSaved(String path, int orientation) {
                        LogUtil.i(TAG, "Image saved path: " + path);
                        // The proxy is what a person looks at right after the shutter, so in
                        // this mode it has to agree with the frame that was actually taken —
                        // the panchromatic plane in the container, not a colour rendering of
                        // modules that were never the point. Done before the gallery is told.
                        if (MonoMode.isActive()) {
                            MonoProxy.greyInPlace(path);
                        }
                        // Astro: measure the stars of the panchromatic plane — the focus
                        // check that the dark preview cannot give.
                        AstroAnalyzer.onSaved(path);
                        if (mOnImageStatusListener != null) {
                            mOnImageStatusListener.onImageSaved(format, path, orientation);
                        }
                    }

                    @Override
                    public void onComplete(boolean success) {
                        reader.close();
                        boolean isYuvFormat = format == ImageFormat.YUV_420_888;
                        if (isYuvFormat == CameraApp.isLight()) {
                            CameraManager.get().decrementCapturesInFlight();
                            LogUtil.i(TAG, "RAW File saved, pending captures: "
                                    + CameraManager.get().getCapturesInFlight());
                        }
                        if (isYuvFormat) {
                            LogUtil.i(TAG, "RAW File saved onComplete: " + success);
                            mTimingLoggerUtil.captureTiming(
                                    TimingLoggerUtil.TimeToCaptureSplits.RAW_SAVED);
                        } else {
                            LogUtil.i(TAG, "Jpeg File saved onComplete: " + success);
                            mTimingLoggerUtil.captureTiming(
                                    TimingLoggerUtil.TimeToCaptureSplits.JPEG_SAVED);
                        }
                        if (mPendingCapturesCompleteListener != null) {
                            mPendingCapturesCompleteListener.onComplete();
                        }
                    }
                });
                handleCompletionLocked(entry.getKey(), builder, resultQueue);
            } catch (IllegalStateException e) {
                LogUtil.e(TAG, "Too many images queued for saving, dropping image for request: "
                        + entry.getKey(), e);
                reader.close();
                resultQueue.remove(entry.getKey());
                CameraManager.get().decrementCapturesInFlight();
            }
        }
    }

    private Size getJpegAndYuvSize() {
        String aspectRatio = mCameraPref.getStringValue(CamPrefsUtils.CAM_ASPECT_RATIO);
        Size defaultSize = new Size(MAX_PREVIEW_WIDTH_4_3, MAX_PREVIEW_HEIGHT);
        switch (aspectRatio) {
            case "4:3":
                return defaultSize;
            case "1:1":
                return new Size(MAX_PREVIEW_HEIGHT, MAX_PREVIEW_HEIGHT);
            case "16:9":
                return new Size(MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT);
            default:
                return defaultSize;
        }
    }
}