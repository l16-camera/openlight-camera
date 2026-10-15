package openlight.co.camera;

import java.util.HashMap;
import java.util.Map;
import openlight.co.camera.managers.mono.MonoMode;
import openlight.co.lib.utils.FeatureManager;

public enum CameraMode {

    AUTO(true, Res.MODE_AUTO, Res.MODE_AUTO, false, false, true) {
        @Override
        public boolean isAuto() { return true; }
    },
    ISO(true, Res.MODE_ISO, Res.MODE_SHORT_ISO, true, false, true) {
        @Override
        public boolean isIso() { return true; }
    },
    SHUTTER(true, Res.MODE_SHUTTER, Res.MODE_SHORT_SHUTTER, false, true, true) {
        @Override
        public boolean isShutter() { return true; }
    },
    MANUAL(true, Res.MODE_MANUAL, Res.MODE_MANUAL, true, true, false) {
        @Override
        public boolean isManual() { return true; }
    },
    /**
     * Monochrome. Exposure behaves like AUTO; what the mode changes is which modules
     * take the shot — see {@link MonoMode}. Gated on the flag alone rather than on
     * {@link MonoMode#isSupported()}, because this runs in the static initialiser and
     * must not touch the application object.
     */
    MONO(MonoMode.isFeatureEnabled(), Res.MODE_MONO, Res.MODE_SHORT_MONO, false, false, true) {
        @Override
        public boolean isMono() { return true; }
    },
    VIDEO(FeatureManager.get().getBoolean("video.feature", true), Res.MODE_VIDEO_BETA, Res.MODE_VIDEO,
            false, false, false) {
        @Override
        public boolean isVideo() { return true; }
    };

    /**
     * Resource ids, inlined the way the other migrated enums do it: apktool rebuilds
     * res/ but not R.smali, so a newly added string has no generated field to import.
     * They live in their own class because an enum constant may not reference a static
     * field of the enum itself. mode_mono and mode_short_mono are declared in
     * res/values/strings.xml with ids pinned in res/values/public.xml.
     */
    private static final class Res {
        static final int MODE_AUTO = 0x7f0e00e1;
        static final int MODE_ISO = 0x7f0e00e2;
        static final int MODE_MANUAL = 0x7f0e00e3;
        static final int MODE_SHORT_ISO = 0x7f0e00e4;
        static final int MODE_SHORT_SHUTTER = 0x7f0e00e5;
        static final int MODE_SHUTTER = 0x7f0e00e6;
        static final int MODE_VIDEO = 0x7f0e00e8;
        static final int MODE_VIDEO_BETA = 0x7f0e00e9;
        static final int MODE_MONO = 0x7f0e0142;
        static final int MODE_SHORT_MONO = 0x7f0e0143;
    }

    private static Map<Integer, CameraMode> mEnabledModes;

    private final boolean mEvAdjustable;
    private final boolean mIsEnabled;
    private final boolean mIsoAdjustable;
    private final int mResId;
    private final int mShortResId;
    private final boolean mSsAdjustable;

    CameraMode(boolean isEnabled, int resId, int shortResId,
               boolean isoAdjustable, boolean ssAdjustable, boolean evAdjustable) {
        mIsEnabled = isEnabled;
        mResId = resId;
        mShortResId = shortResId;
        mIsoAdjustable = isoAdjustable;
        mSsAdjustable = ssAdjustable;
        mEvAdjustable = evAdjustable;
    }

    public String getLabel() {
        return CameraApp.get().getResources().getString(mResId);
    }

    public String getShortLabel() {
        return CameraApp.get().getResources().getString(mShortResId);
    }

    public boolean isEvAdjustable() { return mEvAdjustable; }
    public boolean isIsoAdjustable() { return mIsoAdjustable; }
    public boolean isSsAdjustable() { return mSsAdjustable; }
    public boolean isEnabled() { return mIsEnabled; }

    public boolean isAuto() { return false; }
    public boolean isIso() { return false; }
    public boolean isManual() { return false; }
    public boolean isMono() { return false; }
    public boolean isShutter() { return false; }
    public boolean isVideo() { return false; }

    public static Map<Integer, CameraMode> getEnabledModes() {
        if (mEnabledModes == null) {
            mEnabledModes = new HashMap<>();
            int index = 0;
            for (CameraMode mode : values()) {
                if (mode.isEnabled()) {
                    mEnabledModes.put(index, mode);
                    index++;
                }
            }
        }
        return mEnabledModes;
    }

    public static CameraMode getModeByLabel(String label) {
        if (label != null) {
            // Case-insensitive: the CI intent sends whatever the caller typed, and an
            // uncaught miss here takes the whole app down through the restart handler.
            String lower = label.toLowerCase();
            for (CameraMode mode : values()) {
                if (lower.contains(mode.toString().toLowerCase())) {
                    return mode;
                }
            }
        }
        throw new IllegalArgumentException("No matching mode found for label " + label);
    }

    public static CameraMode getMode(String mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode string is null");
        }
        try {
            return valueOf(mode);
        } catch (IllegalArgumentException e) {
            return getModeByLabel(mode);
        }
    }

    public static CameraMode forIndex(int index) {
        return getEnabledModes().get(index);
    }

    public static int indexForMode(CameraMode mode) {
        for (Map.Entry<Integer, CameraMode> entry : getEnabledModes().entrySet()) {
            if (entry.getValue().equals(mode)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("No matching index found for mode " + mode);
    }

    public static String[] getLabels() {
        Map<Integer, CameraMode> modes = getEnabledModes();
        String[] labels = new String[modes.size()];
        for (Map.Entry<Integer, CameraMode> entry : modes.entrySet()) {
            labels[entry.getKey()] = entry.getValue().getLabel();
        }
        return labels;
    }

    public static String[] getShortLabels() {
        Map<Integer, CameraMode> modes = getEnabledModes();
        String[] labels = new String[modes.size()];
        for (Map.Entry<Integer, CameraMode> entry : modes.entrySet()) {
            labels[entry.getKey()] = entry.getValue().getShortLabel();
        }
        return labels;
    }

    public static int getMaxIndex() {
        return getEnabledModes().size() - 1;
    }

    public static boolean isPictureMode(CameraMode mode) {
        return mode == AUTO || mode == ISO || mode == SHUTTER || mode == MANUAL || mode == MONO;
    }

    public static boolean isVideoMode(CameraMode mode) {
        return mode == VIDEO;
    }
}
