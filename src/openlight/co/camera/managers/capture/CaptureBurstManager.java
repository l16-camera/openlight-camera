package openlight.co.camera.managers.capture;

import android.hardware.camera2.CaptureRequest;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Constructor;
import openlight.co.camera.enums.BurstMode;
import openlight.co.lib.utils.FeatureManager;
import openlight.co.lib.content.CamPrefsFactory;
import openlight.co.lib.content.Prefs;

public class CaptureBurstManager {
    private static final String BURST_DISABLE_FILE = "disable_burst.prop";
    private static final int BURST_SIX_SHOT_CAPTURE_INTENT = 12;
    private static final int BURST_THREE_SHOT_CAPTURE_INTENT = 13;
    private static final int BURST_TRANSFER_CAPTURE_INTENT = 11;

    // Light's SDK refuses anything below 2 (CaptureRequest$Builder.setBurstFps).
    private static final int BURST_FPS_MIN = 2;
    private static final String TAG = "BurstFps";

    private static CaptureBurstManager sInstance;

    private final Prefs mCamPref = CamPrefsFactory.get();
    private final boolean mIsBurstDisabled =
            new File(Environment.getExternalStorageDirectory(), BURST_DISABLE_FILE).exists();
    private boolean mIsFetchRequest = true;
    private int mBurstOrientation = 0;

    public static synchronized CaptureBurstManager get() {
        synchronized (CaptureBurstManager.class) {
            if (sInstance == null) {
                sInstance = new CaptureBurstManager();
            }
            return sInstance;
        }
    }

    private CaptureBurstManager() {
        if (mIsBurstDisabled) {
            mCamPref.removeValue("burst_mode");
        }
    }

    public int getPendingUserCaptureCount() {
        BurstMode mode = BurstMode.getModeByPrefsKey(mCamPref.getStringValue("burst_mode"));
        switch (mode) {
            case CAPTURE_SIX:
                return 6;
            case CAPTURE_THREE:
                return 3;
            default:
                return 0;
        }
    }

    void setCaptureRequestForBurst(CaptureRequest.Builder builder, int burstIndex) {
        BurstMode mode = BurstMode.getModeByPrefsKey(mCamPref.getStringValue("burst_mode"));
        mIsFetchRequest = true;
        int captureIntent;
        switch (mode) {
            case CAPTURE_SIX:
                if (burstIndex == 6) {
                    mIsFetchRequest = false;
                    captureIntent = BURST_SIX_SHOT_CAPTURE_INTENT;
                } else {
                    captureIntent = BURST_TRANSFER_CAPTURE_INTENT;
                }
                break;
            case CAPTURE_THREE:
                if (burstIndex == 3) {
                    mIsFetchRequest = false;
                    captureIntent = BURST_THREE_SHOT_CAPTURE_INTENT;
                } else {
                    captureIntent = BURST_TRANSFER_CAPTURE_INTENT;
                }
                break;
            default:
                captureIntent = BURST_TRANSFER_CAPTURE_INTENT;
                break;
        }
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, captureIntent);
        applyBurstFps(builder, captureIntent);
    }

    /**
     * features.prop {@code burst.fps}: frame rate the ASIC uses for a burst.
     * The HAL reads vendor tag co.light.burst_fps in translateLightCaptureIntent;
     * nothing in Light's camera app ever set it. 0 leaves the request untouched.
     */
    private static void applyBurstFps(CaptureRequest.Builder builder, int captureIntent) {
        int fps = FeatureManager.get().getInt("burst.fps", 0);
        if (fps < BURST_FPS_MIN) {
            return;
        }
        try {
            Constructor<?> c = CaptureRequest.Key.class.getConstructor(String.class, Class.class);
            @SuppressWarnings("unchecked")
            CaptureRequest.Key<Integer> key =
                    (CaptureRequest.Key<Integer>) c.newInstance("co.light.burst_fps", Integer.class);
            builder.set(key, fps);
            Log.i(TAG, "co.light.burst_fps=" + fps + " on capture intent " + captureIntent);
        } catch (Throwable t) {
            Log.e(TAG, "co.light.burst_fps not set", t);
        }
    }

    public boolean getIfFetchRequest() {
        return mIsFetchRequest;
    }

    public void setBurstOrientation(int orientation) {
        mBurstOrientation = orientation;
    }

    public int getBurstOrientation() {
        return mBurstOrientation;
    }
}