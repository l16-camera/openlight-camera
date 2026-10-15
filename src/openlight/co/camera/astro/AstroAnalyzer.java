package openlight.co.camera.astro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import openlight.co.camera.CameraApp;
import openlight.co.camera.managers.CameraManager;
import openlight.co.lib.utils.LogUtil;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * After each astro-mode shot: pull the panchromatic plane out of the .lri and say how
 * many stars there are and how sharp. That number is the focus check in the field —
 * the preview cannot show a star at night, the A2 plane can.
 *
 * Runs on its own thread (≈0.1 s to read and unpack the plane, ≈1.5 s to find the
 * stars on the L16); writes L16_xxxxx.astro.json next to the frame for the desk.
 */
public final class AstroAnalyzer {
    private static final String TAG = "AstroAnalyzer";
    private static final int TOP_STARS = 25;

    private static Handler sWorker;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    /**
     * Re-run the analysis on a frame already on the card, for checks without a night:
     * adb shell am broadcast -a openlight.co.intent.ASTRO_ANALYZE --es path /sdcard/DCIM/Camera/L16_00241.lri
     * Reads only; works in astro mode, while the camera screen is up.
     */
    public static final String ACTION_ANALYZE = "openlight.co.intent.ASTRO_ANALYZE";

    private static BroadcastReceiver sReceiver;

    private AstroAnalyzer() {
    }

    static synchronized void register(Context context) {
        if (sReceiver != null) {
            return;
        }
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String path = intent.getStringExtra("path");
                LogUtil.i(TAG, "[ASTRO] analyse on request: " + path);
                onSaved(path);
            }
        };
        context.registerReceiver(sReceiver, new IntentFilter(ACTION_ANALYZE));
    }

    static synchronized void unregister(Context context) {
        if (sReceiver != null) {
            try {
                context.unregisterReceiver(sReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            sReceiver = null;
        }
    }

    /** Called with every saved file; acts only on .lri in astro mode. */
    public static void onSaved(final String path) {
        if (path == null || !path.endsWith(".lri")
                || !CameraManager.getCameraModeOrDefault().isAstro()) {
            return;
        }
        worker().post(new Runnable() {
            public void run() {
                analyze(path);
            }
        });
    }

    private static synchronized Handler worker() {
        if (sWorker == null) {
            HandlerThread t = new HandlerThread("astro-analyzer");
            t.start();
            sWorker = new Handler(t.getLooper());
        }
        return sWorker;
    }

    private static void analyze(String path) {
        if (!AstroNative.isAvailable()) {
            return;
        }
        long t0 = System.currentTimeMillis();
        Location where = location();
        String json = where != null
                ? AstroNative.solveA2(path, TOP_STARS, where.getLatitude(), where.getLongitude())
                : AstroNative.solveA2(path, TOP_STARS, Double.NaN, Double.NaN);
        long ms = System.currentTimeMillis() - t0;
        LogUtil.i(TAG, "[ASTRO] " + new File(path).getName() + " analysed in " + ms + " ms: " + json);
        writeSidecar(path, json);
        String text;
        String sky = null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.optBoolean("ok")) {
                text = String.format(Locale.ROOT, "%s: звёзд %d, FWHM %.1f px, вытянутость %.2f · %.1f с",
                        o.optString("camera"), o.optInt("n_stars"), o.optDouble("fwhm_median"),
                        o.optDouble("elongation_median"), ms / 1000.0);
                if ("no_stars".equals(o.optString("verdict"))) {
                    text = o.optString("camera") + ": звёзд не видно · " + ms / 1000.0 + " с";
                }
                JSONObject solve = o.optJSONObject("solve");
                if (solve != null) {
                    sky = String.format(Locale.ROOT, "Небо: RA %.2f° Dec %+.2f°, азимут %.0f°, высота %+.0f° (%d пар)",
                            solve.optDouble("ra"), solve.optDouble("dec"), solve.optDouble("azimuth"),
                            solve.optDouble("altitude"), solve.optInt("pairs"));
                } else if (o.has("solve_note")) {
                    sky = "Солв: " + o.optString("solve_note");
                }
                text += "\n" + sky;
            } else {
                text = "Разбор кадра не удался: " + o.optString("error");
            }
        } catch (JSONException e) {
            text = "Разбор кадра не удался";
        }
        final String message = text;
        JSONObject parsed = null;
        try {
            parsed = new JSONObject(json);
        } catch (JSONException ignored) {
        }
        final JSONObject result = parsed;
        AstroSeries.onAnalysed(path, result);
        String head = new File(path).getName().replace(".lri", "");
        if (result != null) {
            double sec = result.optDouble("exposure_ms") / 1000.0;
            head += " · " + (sec < 10 ? String.format(Locale.ROOT, "%.2f с", sec)
                    : String.format(Locale.ROOT, "%.1f с", sec));
            double fwhm = result.optDouble("fwhm_median", Double.NaN);
            if (!Double.isNaN(fwhm)) {
                head += String.format(Locale.ROOT, " · FWHM %.2f px · звёзд %d", fwhm, result.optInt("n_stars"));
            }
        }
        final String title = head;
        String foot = null;
        JSONObject solved = result != null ? result.optJSONObject("solve") : null;
        if (solved != null) {
            foot = String.format(Locale.ROOT, "RA %.2f° Dec %+.2f° · аз %.0f° выс %+.0f° · %d пар · %.1f с",
                    solved.optDouble("ra"), solved.optDouble("dec"), solved.optDouble("azimuth"),
                    solved.optDouble("altitude"), solved.optInt("pairs"), ms / 1000.0);
        } else if (sky != null) {
            foot = sky + String.format(Locale.ROOT, " · %.1f с", ms / 1000.0);
        }
        final String footer = foot;
        sMain.post(new Runnable() {
            public void run() {
                // The panel carries the same lines; a toast on top of it would hide the stars.
                if (result == null || !result.optBoolean("ok")
                        || !CameraManager.getCameraModeOrDefault().isAstro()
                        || !AstroFocusPanel.show(AstroNightUi.activity(), result, title, footer)) {
                    Toast.makeText(CameraApp.get(), message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * Where the camera stands. The app's own fix expires after a while; for the sky an
     * old fix is fine — the site does not move during a night — so fall back to the
     * system's last known location from any provider.
     */
    private static Location location() {
        openlight.co.camera.utils.LocationManager app = openlight.co.camera.utils.LocationManager.get();
        Location l = app != null ? app.getLocation() : null;
        if (l != null) {
            return l;
        }
        try {
            LocationManager lm = (LocationManager) CameraApp.get().getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : lm.getAllProviders()) {
                Location c = lm.getLastKnownLocation(provider);
                if (c != null && (best == null || c.getTime() > best.getTime())) {
                    best = c;
                }
            }
            return best;
        } catch (SecurityException e) {
            LogUtil.w(TAG, "no location permission: " + e);
            return null;
        }
    }

    private static void writeSidecar(String lriPath, String json) {
        String out = lriPath.substring(0, lriPath.length() - 4) + ".astro.json";
        FileOutputStream f = null;
        try {
            f = new FileOutputStream(out);
            f.write(json.getBytes("UTF-8"));
        } catch (IOException e) {
            LogUtil.w(TAG, "cannot write " + out + ": " + e);
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
