package com.buddyapps.utlwa;

import android.content.Context;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

/** Centralised design tokens and small UI helpers for Utlwa!. */
final class Theme {
    // Palette
    static final int BG          = Color.parseColor("#050505");
    static final int SURFACE     = Color.parseColor("#101010");
    static final int CARD        = Color.parseColor("#171717");
    static final int ELEVATED    = Color.parseColor("#202020");
    static final int GOLD        = Color.parseColor("#D4AF37");
    static final int GOLD_LIGHT  = Color.parseColor("#FFD45A");
    static final int GOLD_DEEP   = Color.parseColor("#A87919");
    static final int WHITE       = Color.parseColor("#FFFFFF");
    static final int TEXT_SEC    = Color.parseColor("#B5B5B5");
    static final int MUTED       = Color.parseColor("#777777");
    static final int BORDER      = Color.parseColor("#493716");

    private Theme() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static int sp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** Rounded solid or stroked background. */
    static GradientDrawable rounded(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable stroked(int fill, int stroke, float strokeDp, float radiusDp, Context c) {
        GradientDrawable d = rounded(fill, radiusDp, c);
        d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    /** Warm gold vertical gradient, optionally rounded. */
    static GradientDrawable goldGradient(float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{GOLD_DEEP, GOLD, GOLD_LIGHT, GOLD});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static void goldText(TextView tv, int height) {
        LinearGradient g = new LinearGradient(0, 0, 0, Math.max(1, height),
                GOLD_LIGHT, GOLD_DEEP, Shader.TileMode.CLAMP);
        tv.getPaint().setShader(g);
    }

    static Animation fadeIn(long ms) {
        AlphaAnimation a = new AlphaAnimation(0f, 1f);
        a.setDuration(ms);
        a.setFillAfter(true);
        return a;
    }
}
