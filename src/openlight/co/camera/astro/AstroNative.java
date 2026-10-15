package openlight.co.camera.astro;

import openlight.co.lib.utils.LogUtil;

/** Bridge to native/astro-core (libopenlight_astro.so, built by its build-android.sh). */
public final class AstroNative {
    private static final String TAG = "AstroNative";
    private static Boolean sLoaded;

    private AstroNative() {
    }

    public static synchronized boolean isAvailable() {
        if (sLoaded == null) {
            try {
                System.loadLibrary("openlight_astro");
                sLoaded = Boolean.TRUE;
            } catch (UnsatisfiedLinkError e) {
                LogUtil.w(TAG, "libopenlight_astro.so not loaded: " + e);
                sLoaded = Boolean.FALSE;
            }
        }
        return sLoaded;
    }

    /**
     * Read the panchromatic plane of a fresh .lri and measure its stars. Returns JSON:
     * {"ok":true, "camera":"A2", "n_stars":…, "fwhm_median":…, "elongation_median":…,
     * "stars":[[x,y,flux,hfr,elongation],…], …} or {"ok":false,"error":…}.
     */
    public static native String analyzeA2(String lriPath, int topStars);

    /**
     * Same, then plate-solve the A2 plane: time and gravity from the frame, place from
     * the phone (NaN when unknown). Adds "solve":{ra,dec,rotation,azimuth,altitude,
     * pairs,…} on success, or "solve_note" saying why not.
     */
    public static native String solveA2(String lriPath, int topStars, double lat, double lon);
}
