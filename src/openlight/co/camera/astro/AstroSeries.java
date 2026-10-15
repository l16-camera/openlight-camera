package openlight.co.camera.astro;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import openlight.co.camera.managers.CameraManager;
import openlight.co.camera.managers.capture.CaptureManager;
import openlight.co.lib.utils.FeatureManager;
import openlight.co.lib.utils.LogUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Intervalometer for astro mode: N light frames, then K dark frames at the same
 * settings and, as near as possible, the same temperature.
 *
 * Started from the focus panel once a test frame looks right. Each frame goes through
 * the normal shutter path (CaptureManager.doCapture, self-timer included); the next one
 * is fired when the previous frame's analysis is in, plus the pause. The sensor heats
 * through a series (39 → 47 °C over four 19.45 s frames, astro-plan.md), and dark
 * current doubles every 6–8 °C, so a frame at or above astro.series.max_temp is followed
 * by a cooling pause. After the lights the banner asks to cover the lenses; a tap
 * starts the darks, and a dark with more than a trace of sky in it is flagged.
 *
 * Everything lands in DCIM/Camera/astro-series-<time>.json — which files are lights and
 * which darks, their temperature, FWHM and solve — for stacking on the desk.
 *
 * Settings, features.prop: astro.series.frames (10), astro.series.pause_s (0),
 * astro.series.darks (5), astro.series.max_temp (48), astro.series.cool_s (90).
 */
public final class AstroSeries {
    private static final String TAG = "AstroSeries";
    private static final String DCIM = "/sdcard/DCIM/Camera";
    private static final String BANNER_TAG = "astro-series-banner";

    private enum State { IDLE, LIGHTS, WAIT_COVER, DARKS }

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static State sState = State.IDLE;
    private static int sFrames;
    private static int sDarks;
    private static int sPauseS;
    private static int sMaxTemp;
    private static int sCoolS;
    private static int sDone;
    private static File sLog;
    private static JSONObject sDoc;
    private static final List<Double> sLightBackground = new ArrayList<>();
    private static String sNote = "";

    private AstroSeries() {
    }

    public static boolean isRunning() {
        return sState != State.IDLE;
    }

    /** What the panel's button says. */
    static String describe() {
        FeatureManager f = FeatureManager.get();
        return String.format(Locale.ROOT, "▶ серия: %d кадров, пауза %d с, тёмных %d",
                f.getInt("astro.series.frames", 10), f.getInt("astro.series.pause_s", 0),
                f.getInt("astro.series.darks", 5));
    }

    /** UI thread. */
    static void start(Activity activity) {
        if (isRunning() || !CameraManager.getCameraModeOrDefault().isAstro()) {
            return;
        }
        FeatureManager f = FeatureManager.get();
        sFrames = Math.max(1, f.getInt("astro.series.frames", 10));
        sDarks = Math.max(0, f.getInt("astro.series.darks", 5));
        sPauseS = Math.max(0, f.getInt("astro.series.pause_s", 0));
        sMaxTemp = f.getInt("astro.series.max_temp", 48);
        sCoolS = Math.max(0, f.getInt("astro.series.cool_s", 90));
        sDone = 0;
        sNote = "";
        sLightBackground.clear();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        sLog = new File(DCIM, "astro-series-" + stamp + ".json");
        sDoc = new JSONObject();
        try {
            sDoc.put("started", stamp);
            sDoc.put("frames_planned", sFrames);
            sDoc.put("darks_planned", sDarks);
            sDoc.put("pause_s", sPauseS);
            sDoc.put("max_temp", sMaxTemp);
            sDoc.put("frames", new JSONArray());
        } catch (JSONException ignored) {
        }
        sState = State.LIGHTS;
        LogUtil.i(TAG, "[ASTRO] series start: " + sFrames + " lights, " + sDarks + " darks, log " + sLog);
        AstroFocusPanel.hide(activity);
        banner();
        fireIn(1000);
    }

    /** User or app ended it. Any thread. */
    public static void stop(final String why) {
        if (!isRunning()) {
            return;
        }
        LogUtil.i(TAG, "[ASTRO] series stopped: " + why);
        finish("stopped: " + why);
    }

    /** A frame's analysis is in. Worker thread. */
    static void onAnalysed(String path, JSONObject result) {
        if (sState != State.LIGHTS && sState != State.DARKS) {
            return;
        }
        boolean dark = sState == State.DARKS;
        int temp = result != null ? result.optInt("temperature", Integer.MIN_VALUE) : Integer.MIN_VALUE;
        double bg = result != null ? result.optDouble("background", Double.NaN) : Double.NaN;
        JSONObject entry = new JSONObject();
        try {
            entry.put("file", new File(path).getName());
            entry.put("role", dark ? "dark" : "light");
            if (result != null) {
                entry.put("temperature", temp);
                entry.put("exposure_ms", result.optDouble("exposure_ms"));
                entry.put("analog_gain", result.optDouble("analog_gain"));
                entry.put("background", bg);
                entry.put("n_stars", result.optInt("n_stars"));
                entry.put("fwhm", result.optDouble("fwhm_median"));
                if (result.has("solve")) {
                    entry.put("solve", result.getJSONObject("solve"));
                }
            }
        } catch (JSONException ignored) {
        }
        sNote = "";
        if (dark) {
            double sky = median(sLightBackground);
            // A covered lens leaves read noise and dark current; anything like the sky
            // level means the cap leaks or a module is uncovered.
            if (!Double.isNaN(bg) && !Double.isNaN(sky) && bg > 3.0 && bg > 0.5 * sky) {
                sNote = String.format(Locale.ROOT, "тёмный кадр светится: фон %.0f при небе %.0f", bg, sky);
                try {
                    entry.put("warning", "light leak");
                } catch (JSONException ignored) {
                }
            }
        } else if (!Double.isNaN(bg)) {
            sLightBackground.add(bg);
        }
        sDoc.optJSONArray("frames").put(entry);
        sDone++;
        save();

        if (!dark && sDone >= sFrames) {
            sDone = 0;
            if (sDarks == 0) {
                finish("done");
                return;
            }
            sState = State.WAIT_COVER;
            sNote = "";
            banner();
            return;
        }
        if (dark && sDone >= sDarks) {
            finish("done");
            return;
        }
        long delay = sPauseS * 1000L;
        if (temp != Integer.MIN_VALUE && temp >= sMaxTemp) {
            delay = Math.max(delay, sCoolS * 1000L);
            String cool = String.format(Locale.ROOT, "%d °C — пауза %d с на остывание", temp, sCoolS);
            sNote = sNote.isEmpty() ? cool : sNote + "\n" + cool;
            LogUtil.i(TAG, "[ASTRO] series: " + cool);
        }
        banner();
        fireIn(delay);
    }

    private static void startDarks() {
        if (sState != State.WAIT_COVER) {
            return;
        }
        sState = State.DARKS;
        sDone = 0;
        LogUtil.i(TAG, "[ASTRO] series: darks");
        banner();
        fireIn(1000);
    }

    private static void fireIn(long ms) {
        sMain.postDelayed(new Runnable() {
            public void run() {
                fire(0);
            }
        }, ms);
    }

    /** Shoot, waiting out a busy camera for a while. Main thread. */
    private static void fire(final int tries) {
        if (sState != State.LIGHTS && sState != State.DARKS) {
            return;
        }
        if (!CameraManager.getCameraModeOrDefault().isAstro()) {
            stop("left astro mode");
            return;
        }
        CaptureManager cm = CaptureManager.get();
        if (!cm.isIdle()) {
            if (tries < 60) {
                sMain.postDelayed(new Runnable() {
                    public void run() {
                        fire(tries + 1);
                    }
                }, 500);
            } else {
                stop("camera stayed busy");
            }
            return;
        }
        LogUtil.i(TAG, "[ASTRO] series: " + (sState == State.DARKS ? "dark " : "light ") + (sDone + 1));
        cm.doCapture();
    }

    private static void finish(String how) {
        try {
            if (sDoc != null) {
                sDoc.put("finished", new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()));
                sDoc.put("result", how);
            }
        } catch (JSONException ignored) {
        }
        save();
        sState = State.IDLE;
        LogUtil.i(TAG, "[ASTRO] series " + how + ", log " + sLog);
        sMain.post(new Runnable() {
            public void run() {
                showBanner("серия: " + (sLog != null ? sLog.getName() : "") , true);
            }
        });
    }

    private static void save() {
        if (sLog == null || sDoc == null) {
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(sLog);
            out.write(sDoc.toString(1).getBytes("UTF-8"));
        } catch (IOException | JSONException e) {
            LogUtil.w(TAG, "cannot write " + sLog + ": " + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void banner() {
        final String text;
        switch (sState) {
            case LIGHTS:
                text = String.format(Locale.ROOT, "серия: кадр %d из %d", sDone + 1, sFrames);
                break;
            case WAIT_COVER:
                text = "закройте объективы и коснитесь здесь — тёмные кадры (" + sDarks + ")";
                break;
            case DARKS:
                text = String.format(Locale.ROOT, "тёмный кадр %d из %d", sDone + 1, sDarks);
                break;
            default:
                return;
        }
        final String full = text + (sNote.isEmpty() ? "" : "\n" + sNote)
                + (sState == State.WAIT_COVER ? "" : "\nудерживайте, чтобы остановить");
        sMain.post(new Runnable() {
            public void run() {
                showBanner(full, false);
            }
        });
    }

    /** Main thread. `closing` shows a last word that goes away by itself. */
    private static void showBanner(String text, boolean closing) {
        Activity a = AstroNightUi.activity();
        if (a == null) {
            return;
        }
        ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        TextView v = (TextView) root.findViewWithTag(BANNER_TAG);
        if (v == null) {
            v = new TextView(a);
            v.setTag(BANNER_TAG);
            v.setTextColor(Color.WHITE);
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28);
            v.setMaxWidth(700);
            v.setBackgroundColor(Color.argb(225, 0, 0, 0));
            v.setPadding(28, 16, 28, 16);
            v.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            // Upper right of the preview: the focus panel takes the left part.
            lp.topMargin = 16;
            lp.rightMargin = 256;
            root.addView(v, lp);
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    startDarks();
                }
            });
            v.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View view) {
                    stop("by user");
                    return true;
                }
            });
        }
        v.setText(text);
        v.bringToFront();
        if (closing) {
            final TextView done = v;
            sMain.postDelayed(new Runnable() {
                public void run() {
                    if (!isRunning() && done.getParent() != null) {
                        ((ViewGroup) done.getParent()).removeView(done);
                    }
                }
            }, 6000);
        }
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) {
            return Double.NaN;
        }
        List<Double> s = new ArrayList<>(v);
        java.util.Collections.sort(s);
        return s.get(s.size() / 2);
    }
}
