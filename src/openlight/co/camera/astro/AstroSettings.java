package openlight.co.camera.astro;

import openlight.co.lib.content.CamPrefsFactory;
import openlight.co.lib.content.Prefs;
import openlight.co.lib.utils.LogUtil;

/**
 * Astro mode's own exposure and timer.
 *
 * Astro borrows MANUAL's dials, and those keep their values in shared preferences
 * (shutter_index, iso_index) together with the self-timer (timer_setting). Left alone,
 * astro would open at whatever manual was last set to — 1/10 s in practice — and
 * whatever astro was set to would leak back into manual. So the three values are
 * swapped on the way in and out: astro keeps its own copy, the other modes get theirs
 * back.
 *
 * Astro's first-time values: 20 s (the sensor stops at 19.45 s anyway, L16_00231/295),
 * ISO 800 (the analog ceiling, 7.75×; L16_00233) and a 3 s timer so the press does not
 * shake the tripod.
 */
public final class AstroSettings {
    private static final String TAG = "AstroSettings";

    private static final String[] KEYS = {"shutter_index", "iso_index", "timer_setting"};
    /** Constants.exposureTimes[2] = 20 s; [0] is 29.98 s and records the same 19.45 s. */
    private static final String[] ASTRO_DEFAULTS = {"2", "11", "3s"};
    private static final boolean[] IS_INT = {true, true, false};

    private static final String ASTRO_PREFIX = "astro.";
    private static final String OTHER_PREFIX = "astro.other.";

    private AstroSettings() {
    }

    public static void onEnter() {
        Prefs p = CamPrefsFactory.get();
        StringBuilder log = new StringBuilder("[ASTRO] settings in:");
        for (int i = 0; i < KEYS.length; i++) {
            store(p, OTHER_PREFIX + KEYS[i], read(p, KEYS[i], IS_INT[i]));
            String mine = p.getStringValue(ASTRO_PREFIX + KEYS[i]);
            String v = present(mine) ? mine : ASTRO_DEFAULTS[i];
            write(p, KEYS[i], v, IS_INT[i]);
            log.append(' ').append(KEYS[i]).append('=').append(v);
        }
        LogUtil.i(TAG, log.toString());
    }

    public static void onLeave() {
        Prefs p = CamPrefsFactory.get();
        for (int i = 0; i < KEYS.length; i++) {
            store(p, ASTRO_PREFIX + KEYS[i], read(p, KEYS[i], IS_INT[i]));
            String other = p.getStringValue(OTHER_PREFIX + KEYS[i]);
            if (present(other)) {
                write(p, KEYS[i], other, IS_INT[i]);
            } else {
                p.removeValue(KEYS[i]);
            }
        }
        LogUtil.i(TAG, "[ASTRO] settings out: other modes' values restored");
    }

    /** Prefs answer "" for a key that was never set. */
    private static boolean present(String s) {
        return s != null && !s.isEmpty();
    }

    private static String read(Prefs p, String key, boolean isInt) {
        return isInt ? Integer.toString(p.getIntValue(key)) : p.getStringValue(key);
    }

    private static void store(Prefs p, String key, String value) {
        if (!present(value)) {
            p.removeValue(key);
        } else {
            p.putValue(key, value);
        }
    }

    private static void write(Prefs p, String key, String value, boolean isInt) {
        if (isInt) {
            p.putValue(key, Integer.parseInt(value));
        } else {
            p.putValue(key, value);
        }
    }
}
