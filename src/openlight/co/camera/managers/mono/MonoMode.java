package openlight.co.camera.managers.mono;

import openlight.co.camera.CameraApp;
import openlight.co.camera.CameraMode;
import openlight.co.lib.content.CamPrefsFactory;
import openlight.co.lib.utils.FeatureManager;

/**
 * True monochrome support.
 *
 * Two of the sixteen modules carry an AR1335 with no colour filter array: A2 rides
 * with the 28 mm group, C6 with the 150 mm one. Their planes are panchromatic — full
 * 4160x3120, no demosaic, and about 1.2 stops more light than a colour module on the
 * same scene. A panchromatic plane rides along with every capture — A2 below 70 mm, C6
 * from 70 mm up (corpus census, 273 .lri, 2026-10-15) — but only at 28 and 150 mm does
 * it see the same field as the frame, which is why this mode offers exactly those two
 * focal lengths rather than the usual range.
 *
 * The camera app cannot render a monochrome JPEG itself — MonoFusion lives in the
 * gallery's libcp.so and the app carries no native libraries of its own. What the mode
 * guarantees is that a panchromatic module takes part in the shot and that the frame
 * reaches the .lri, where scripts/lri-mono pulls it out.
 */
public final class MonoMode {

    /** Focal lengths, in 35 mm equivalent, whose module group contains a mono sensor. */
    public static final float FOCAL_WIDE = 28.0f;
    public static final float FOCAL_TELE = 150.0f;

    /** Camera ids of the panchromatic modules, for the HUD. */
    public static final String MODULE_WIDE = "A2";
    public static final String MODULE_TELE = "C6";

    /** Split point between the two stops, geometric so it sits mid-way on a zoom scale. */
    private static final float FOCAL_SPLIT = (float) Math.sqrt(FOCAL_WIDE * FOCAL_TELE);

    /** The zoom code carries focal lengths in tenths of a millimetre of real lens. */
    private static final float FOCAL_35_TO_LENS = 10.0f;

    private static final String FEATURE_KEY = "mono.feature";
    private static final String MODE_PREF_KEY = "camera_mode_setting";

    private MonoMode() {
    }

    /**
     * Flag only, with no look at the hardware — safe to call from a static initialiser,
     * which is where CameraMode needs it.
     */
    public static boolean isFeatureEnabled() {
        return FeatureManager.get().getBoolean(FEATURE_KEY, true);
    }

    /** Whether this device has the modules at all. They are L16-only. */
    public static boolean isSupported() {
        return isFeatureEnabled() && CameraApp.isLight();
    }

    /**
     * The camera mode is the switch: there is no separate preference to fall out of
     * step with it.
     */
    public static boolean isActive() {
        if (!isSupported()) {
            return false;
        }
        String mode = CamPrefsFactory.get().getStringValue(MODE_PREF_KEY);
        if (mode == null) {
            return false;
        }
        try {
            return CameraMode.getMode(mode).isMono();
        } catch (IllegalArgumentException e) {
            // Unrecognised stored mode: treat as not monochrome rather than take the
            // request path down with us — this runs on every capture request.
            return false;
        }
    }

    /** The two stops the wheel offers in this mode, in 35 mm equivalent. */
    public static float[] focalLengths() {
        return new float[] { FOCAL_WIDE, FOCAL_TELE };
    }

    /** Nearest stop that actually carries a panchromatic module. */
    public static float snapFocalLength(float focal35) {
        return focal35 < FOCAL_SPLIT ? FOCAL_WIDE : FOCAL_TELE;
    }

    /** Same, expressed as the lens focal length the capture request wants. */
    public static float snapLensFocalLength(float lensFocalLength) {
        return snapFocalLength(lensFocalLength * FOCAL_35_TO_LENS) / FOCAL_35_TO_LENS;
    }

    /** Which module the panchromatic plane will come from, for display. */
    public static String moduleFor(float focal35) {
        return snapFocalLength(focal35) == FOCAL_WIDE ? MODULE_WIDE : MODULE_TELE;
    }
}
