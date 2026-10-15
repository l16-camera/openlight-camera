package openlight.co.camera.astro;

import android.app.Activity;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.View;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import openlight.co.camera.managers.CameraManager;
import openlight.co.lib.utils.FeatureManager;
import openlight.co.lib.utils.LogUtil;

/**
 * Night screen for astro mode: everything red, and dim.
 *
 * Dark adaptation takes twenty minutes and one glance at a white screen undoes it; rod
 * cells barely see deep red. So in astro mode the whole window — controls, HUD and the
 * preview itself — goes through a colour matrix that puts each pixel's luminance into
 * the red channel only, and the window brightness drops to a few percent.
 *
 * The filter sits on the decor view as a hardware layer, which is what makes it reach
 * the TextureView preview too. Toasts are separate windows and stay as they are.
 *
 * Follows the mode by itself: {@link #update()} is cheap and is called with every
 * preview result; it only touches the window when astro mode is entered or left.
 * Off with astro.night=false in features.prop.
 */
public final class AstroNightUi {
    private static final String TAG = "AstroNightUi";

    /** Window brightness in astro mode, 0..1. Enough to read the HUD in the dark. */
    private static final float NIGHT_BRIGHTNESS = 0.05f;

    private static WeakReference<Activity> sActivity;
    private static Boolean sApplied;

    private AstroNightUi() {
    }

    public static void attach(Activity activity) {
        sActivity = new WeakReference<>(activity);
        sApplied = null;
        AstroAnalyzer.register(activity.getApplicationContext());
        update();
    }

    public static void detach(Activity activity) {
        AstroSeries.stop("camera screen closed");
        apply(activity, false);
        AstroAnalyzer.unregister(activity.getApplicationContext());
        sActivity = null;
        sApplied = null;
    }

    /** The camera activity while it is attached, for astro views. */
    static Activity activity() {
        return sActivity != null ? sActivity.get() : null;
    }

    private static boolean wanted() {
        return FeatureManager.get().getBoolean("astro.night", true)
                && CameraManager.getCameraModeOrDefault().isAstro();
    }

    /** Bring the window in line with the current mode. Any thread. */
    public static void update() {
        final Activity activity = sActivity != null ? sActivity.get() : null;
        if (activity == null) {
            return;
        }
        final boolean want = wanted();
        if (sApplied != null && sApplied == want) {
            return;
        }
        sApplied = want;
        activity.runOnUiThread(new Runnable() {
            public void run() {
                apply(activity, want);
            }
        });
    }

    private static void apply(Activity activity, boolean on) {
        View root = activity.getWindow().getDecorView();
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        if (on) {
            // Rec. 601 luminance into red; green and blue dark.
            ColorMatrix m = new ColorMatrix(new float[] {
                    0.299f, 0.587f, 0.114f, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 1, 0,
            });
            Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(m));
            root.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
            lp.screenBrightness = NIGHT_BRIGHTNESS;
        } else {
            AstroFocusPanel.hide(activity);
            AstroSeries.stop("left astro mode");
            root.setLayerType(View.LAYER_TYPE_NONE, null);
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        activity.getWindow().setAttributes(lp);
        LogUtil.i(TAG, "[ASTRO] night screen " + (on ? "on" : "off"));
    }
}
