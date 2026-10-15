package openlight.co.camera.astro;

import android.app.Activity;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import openlight.co.camera.managers.CameraManager;
import openlight.co.lib.utils.FeatureManager;
import openlight.co.lib.utils.LogUtil;

/**
 * Night screen for astro mode: the interface red, the whole screen dim.
 *
 * Dark adaptation takes twenty minutes and one glance at a white screen undoes it; rod
 * cells barely see deep red. So in astro mode every control, the HUD and the astro
 * panels go through a colour matrix that puts each pixel's luminance into the red
 * channel only, and the window brightness drops to a few percent. The camera preview
 * keeps its colours (Igor, 15.10: only the UI) — at 5 % brightness it is dim anyway.
 *
 * The filter is a hardware layer on every view that does not hold the preview: the
 * tree is walked from the decor view, groups that contain the TextureView are opened,
 * everything else gets the layer. Views added later (focus panel, series banner, menus)
 * are caught on the next layout pass. Toasts are separate windows and stay as they are.
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
    /** Views that carry our red layer, to take it off again. */
    private static final Set<View> sTinted = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static Paint sPaint;
    private static ViewTreeObserver.OnGlobalLayoutListener sRelayout;

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
        final View root = activity.getWindow().getDecorView();
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        if (on) {
            if (sPaint == null) {
                // Rec. 601 luminance into red; green and blue dark.
                ColorMatrix m = new ColorMatrix(new float[] {
                        0.299f, 0.587f, 0.114f, 0, 0,
                        0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0,
                        0, 0, 0, 1, 0,
                });
                sPaint = new Paint();
                sPaint.setColorFilter(new ColorMatrixColorFilter(m));
            }
            tint(root);
            if (sRelayout == null) {
                sRelayout = new ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        tint(root);
                    }
                };
                root.getViewTreeObserver().addOnGlobalLayoutListener(sRelayout);
            }
            lp.screenBrightness = NIGHT_BRIGHTNESS;
        } else {
            AstroFocusPanel.hide(activity);
            AstroSeries.stop("left astro mode");
            if (sRelayout != null) {
                root.getViewTreeObserver().removeGlobalOnLayoutListener(sRelayout);
                sRelayout = null;
            }
            for (View v : sTinted.toArray(new View[0])) {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
            }
            sTinted.clear();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        activity.getWindow().setAttributes(lp);
        LogUtil.i(TAG, "[ASTRO] night screen " + (on ? "on" : "off") + (on ? ", " + sTinted.size() + " views red" : ""));
    }

    /** Red layer on everything under `v` except the preview. Cheap when nothing changed. */
    private static void tint(View v) {
        if (v instanceof TextureView || v instanceof SurfaceView) {
            return;
        }
        if (v instanceof ViewGroup && holdsPreview(v)) {
            if (sTinted.remove(v)) {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
            }
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                tint(g.getChildAt(i));
            }
            return;
        }
        if (!sTinted.contains(v)) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, sPaint);
            sTinted.add(v);
        }
    }

    private static boolean holdsPreview(View v) {
        if (v instanceof TextureView || v instanceof SurfaceView) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (holdsPreview(g.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }
}
