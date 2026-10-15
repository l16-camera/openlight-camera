package openlight.co.camera.astro;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The focus check after an astro shot, drawn over the preview.
 *
 * The frame is cut 3×3; each cell shows the brightest unclipped star of that part of
 * the A2 plane, blown up so a pixel is a block, with the cell's median FWHM and star
 * count under it. Defocus makes every star a fat disc; a tilted or decentred lens
 * makes one side worse than the other. Under the grid: the histogram of the raw plane
 * (log counts), the sky background and the clipped share — the exposure check.
 *
 * Night mode's colour filter sits on the decor view, so this goes red with the rest.
 * Tap to close; the next analysis replaces it.
 */
public final class AstroFocusPanel extends View {
    private static final int CELL = 196;
    private static final int BUTTON_H = 64;
    private static final int GAP = 8;
    private static final int LABEL = 34;
    private static final int HIST_H = 110;
    private static final int PAD = 14;

    private final Bitmap[] mCrops = new Bitmap[9];
    private final String[] mLabels = new String[9];
    private final int[] mHist;
    private final String mTitle;
    private final String mFooter;
    private final String mLevels;
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPix = new Paint();
    private final Paint mBar = new Paint();
    private final Paint mBack = new Paint();
    private final Paint mButton = new Paint();
    /** Series button, or null while a series runs. */
    private final String mSeries;
    private final Rect mButtonRect = new Rect();

    private AstroFocusPanel(Context context, JSONObject o, String title, String footer) {
        super(context);
        mTitle = title;
        mFooter = footer;
        int n = o.optInt("crop_size", 21);
        JSONArray zones = o.optJSONArray("zones");
        for (int i = 0; i < 9; i++) {
            JSONObject z = zones != null ? zones.optJSONObject(i) : null;
            if (z == null) {
                mLabels[i] = "—";
                continue;
            }
            int count = z.optInt("n");
            double fwhm = z.optDouble("fwhm", Double.NaN);
            mLabels[i] = count == 0 ? "нет звёзд"
                    : String.format(Locale.ROOT, "%.1f px · %d", fwhm, count);
            String b64 = z.optString("crop", "");
            if (!b64.isEmpty()) {
                byte[] px = Base64.decode(b64, Base64.DEFAULT);
                int[] argb = new int[n * n];
                for (int k = 0; k < argb.length && k < px.length; k++) {
                    int v = px[k] & 0xff;
                    argb[k] = 0xff000000 | (v << 16) | (v << 8) | v;
                }
                mCrops[i] = Bitmap.createBitmap(argb, n, n, Bitmap.Config.ARGB_8888);
            }
        }
        JSONArray h = o.optJSONArray("histogram");
        mHist = new int[h != null ? h.length() : 0];
        for (int i = 0; i < mHist.length; i++) {
            mHist[i] = h.optInt(i);
        }
        mLevels = String.format(Locale.ROOT, "фон %.0f ADU · пересвет %.2f %%",
                o.optDouble("background", Double.NaN), o.optDouble("clipped_pct", Double.NaN));

        mText.setColor(Color.WHITE);
        mText.setTextSize(26);
        mDim.setColor(Color.rgb(170, 170, 170));
        mDim.setTextSize(22);
        mPix.setFilterBitmap(false);
        mBar.setColor(Color.rgb(200, 200, 200));
        mBack.setColor(Color.argb(225, 0, 0, 0));
        mButton.setColor(Color.rgb(60, 60, 60));
        mSeries = AstroSeries.isRunning() ? null : AstroSeries.describe();
    }

    static int panelWidth() {
        return 3 * CELL + 2 * GAP + 2 * PAD;
    }

    static int panelHeight() {
        return PAD + 40 + 3 * (CELL + LABEL) + 2 * GAP + GAP + HIST_H + 34 + 32 + BUTTON_H + PAD;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), mBack);
        int y = PAD + 28;
        c.drawText(mTitle, PAD, y, mText);
        y += 12;
        Rect dst = new Rect();
        for (int i = 0; i < 9; i++) {
            int x = PAD + (i % 3) * (CELL + GAP);
            int top = y + (i / 3) * (CELL + LABEL + GAP);
            dst.set(x, top, x + CELL, top + CELL);
            if (mCrops[i] != null) {
                c.drawBitmap(mCrops[i], null, dst, mPix);
            } else {
                c.drawRect(dst, mDim);
                c.drawRect(dst.left + 2, dst.top + 2, dst.right - 2, dst.bottom - 2, mBack);
            }
            c.drawText(mLabels[i], x + 4, top + CELL + 26, mDim);
        }
        y += 3 * (CELL + LABEL + GAP);
        if (mHist.length > 0) {
            double max = 0;
            for (int v : mHist) {
                max = Math.max(max, Math.log10(1 + v));
            }
            int w = panelWidth() - 2 * PAD;
            float bw = (float) w / mHist.length;
            for (int i = 0; i < mHist.length; i++) {
                float hgt = (float) (HIST_H * Math.log10(1 + mHist[i]) / Math.max(max, 1e-9));
                c.drawRect(PAD + i * bw, y + HIST_H - hgt, PAD + (i + 1) * bw - 1, y + HIST_H, mBar);
            }
        }
        c.drawText(mLevels, PAD, y + HIST_H + 28, mDim);
        if (mFooter != null) {
            c.drawText(mFooter, PAD, y + HIST_H + 60, mDim);
        }
        if (mSeries != null) {
            int top = y + HIST_H + 74;
            mButtonRect.set(PAD, top, getWidth() - PAD, top + BUTTON_H - 8);
            c.drawRect(mButtonRect, mButton);
            c.drawText(mSeries, PAD + 16, top + 38, mText);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            boolean series = mSeries != null
                    && mButtonRect.contains((int) event.getX(), (int) event.getY());
            dismiss();
            if (series) {
                AstroSeries.start((Activity) getContext());
            }
        }
        return true;
    }

    private void dismiss() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
    }

    private static final String TAG_VIEW = "astro-focus-panel";

    /** Show the panel for one analysis result; false when it could not be. UI thread. */
    static boolean show(Activity activity, JSONObject o, String title, String footer) {
        if (activity == null || o.optJSONArray("zones") == null) {
            return false;
        }
        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
        if (root == null) {
            return false;
        }
        View old = root.findViewWithTag(TAG_VIEW);
        if (old != null) {
            root.removeView(old);
        }
        AstroFocusPanel p = new AstroFocusPanel(activity, o, title, footer);
        p.setTag(TAG_VIEW);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelWidth(), panelHeight(),
                Gravity.START | Gravity.CENTER_VERTICAL);
        // Left part of the preview, clear of the HUD column; toasts land lower right of it.
        lp.leftMargin = 250;
        root.addView(p, lp);
        return true;
    }

    /** Close it, e.g. when a new shot starts or the mode is left. UI thread. */
    static void hide(Activity activity) {
        if (activity == null) {
            return;
        }
        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
        View old = root != null ? root.findViewWithTag(TAG_VIEW) : null;
        if (old != null) {
            root.removeView(old);
        }
    }
}
