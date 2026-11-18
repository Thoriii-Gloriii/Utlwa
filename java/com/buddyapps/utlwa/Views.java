package com.buddyapps.utlwa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Reusable, custom-drawn UI components for the Utlwa! design system. */
final class Views {

    private Views() {}

    /** Gold-gradient wordmark / brand text. */
    static class GoldTextView extends android.widget.TextView {
        GoldTextView(Context c) {
            super(c);
            setTextColor(Theme.WHITE);
        }
        @Override protected void onDraw(Canvas canvas) {
            Theme.goldText(this, getHeight());
            super.onDraw(canvas);
            getPaint().setShader(null);
        }
    }

    /**
     * Programmatic square/wide artwork. No external images: every seed yields a
     * cinematic dark-gold composition with waves and a warm glow.
     */
    static class ArtworkView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private int seed = 1;
        private float corner = 10f;
        private String url;
        private Bitmap bitmap;
        private static final android.util.LruCache<String, Bitmap> CACHE;
        static {
            int max = (int) (Runtime.getRuntime().maxMemory() / 8);
            CACHE = new android.util.LruCache<String, Bitmap>(max) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
            };
        }

        ArtworkView(Context c) { super(c); setWillNotDraw(false); }
        ArtworkView seed(int s) { seed = s; invalidate(); return this; }
        ArtworkView corner(float dp) { corner = dp; invalidate(); return this; }

        /** Load a real thumbnail (from NewPipe) instead of generated artwork. */
        ArtworkView url(final String u) {
            if (u == null || u.isEmpty()) { url = null; bitmap = null; invalidate(); return this; }
            url = u;
            bitmap = CACHE.get(u);
            if (bitmap != null) { invalidate(); return this; }
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        java.net.HttpURLConnection cn = (java.net.HttpURLConnection)
                                new java.net.URL(u).openConnection();
                        cn.setConnectTimeout(15000);
                        cn.setReadTimeout(20000);
                        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile Safari/537.36");
                        java.io.InputStream in = cn.getInputStream();
                        final Bitmap b = BitmapFactory.decodeStream(in);
                        in.close();
                        if (b != null) {
                            CACHE.put(u, b);
                            post(new Runnable() { @Override public void run() {
                                if (u.equals(url)) { bitmap = b; invalidate(); }
                            }});
                        }
                    } catch (Exception ignored) {}
                }
            }, "utlwa-img").start();
            return this;
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w < 2 || h < 2) return;
            float r = Theme.dp(getContext(), corner);
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            if (bitmap != null) {
                // Centre-crop using src/dst rects so the bitmap is actually scaled.
                float sc = Math.max(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
                float sw = w / sc, sh = h / sc;
                Rect src = new Rect(
                        (int) ((bitmap.getWidth() - sw) / 2),
                        (int) ((bitmap.getHeight() - sh) / 2),
                        (int) ((bitmap.getWidth() + sw) / 2),
                        (int) ((bitmap.getHeight() + sh) / 2));
                canvas.drawBitmap(bitmap, src, new RectF(0, 0, w, h), p);
                canvas.restore();
                return;
            }
            canvas.restore();
            drawGenerated(canvas, w, h);
        }

        private void drawGenerated(Canvas canvas, int w, int h) {
            int a = Math.abs(seed) % 6;
            int[][] pal = {
                {0xFF0E0B05, 0xFF5A4110, 0xFFD4AF37},
                {0xFF0A0A0A, 0xFF3E3E3E, 0xFFC9A227},
                {0xFF120C06, 0xFF7A4E12, 0xFFFFD45A},
                {0xFF05070A, 0xFF2B2B2B, 0xFFB8912E},
                {0xFF0D0B08, 0xFF6B5218, 0xFFE3C15B},
                {0xFF080808, 0xFF4A3410, 0xFFD4AF37}
            };
            float r = Theme.dp(getContext(), corner);
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);

            p.setShader(new LinearGradient(0, 0, w, h, pal[a][0], pal[a][1], Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, w, h, p);

            // Warm light source
            float cx = w * (0.35f + (a % 3) * 0.18f), cy = h * 0.32f;
            p.setShader(new RadialGradient(cx, cy, Math.max(w, h) * 0.75f,
                    pal[a][2] & 0x66FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, Math.max(w, h) * 0.75f, p);

            // Golden sun / record
            float rad = Math.min(w, h) * (0.20f + (a % 2) * 0.05f);
            p.setShader(new RadialGradient(cx, cy, rad, pal[a][2], pal[a][1], Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, rad, p);

            // Sound waves
            p.setShader(null);
            p.setColor(pal[a][2]);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, Theme.dp(getContext(), 1.2f)));
            for (int i = 1; i <= 4; i++) {
                p.setAlpha(150 - i * 28);
                canvas.drawCircle(cx, cy, rad + i * Math.min(w, h) * 0.09f, p);
            }
            p.setAlpha(255);

            // Flowing waveform near the bottom
            p.setColor(pal[a][2]);
            p.setStrokeWidth(Math.max(2f, Theme.dp(getContext(), 2f)));
            Path wave = new Path();
            float base = h * 0.82f;
            wave.moveTo(0, base);
            for (int x = 0; x <= w; x += 8) {
                float t = (x / (float) w) * 6.28f * 2 + seed;
                float y = base + (float) Math.sin(t) * h * 0.05f;
                wave.lineTo(x, y);
            }
            canvas.drawPath(wave, p);

            // Vignette
            p.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                    Color.TRANSPARENT, 0xAA000000, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, w, h, p);
            canvas.restore();
        }
    }

    /** Ear-inspired Utlwa! symbol drawn with a metallic gold stroke. */
    static class LogoView extends View {
        private final Paint gold = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        LogoView(Context c) {
            super(c);
            setWillNotDraw(false);
            gold.setColor(Theme.GOLD_LIGHT);
            gold.setStyle(Paint.Style.STROKE);
            gold.setStrokeCap(Paint.Cap.ROUND);
            gold.setStrokeJoin(Paint.Join.ROUND);
            gold.setShadowLayer(Theme.dp(c, 10), 0, 0, 0x66D4AF37);
            dim.setColor(Theme.GOLD);
            dim.setStyle(Paint.Style.STROKE);
            dim.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float s = Math.min(w, h) / 100f;
            canvas.translate(w / 2f, h / 2f);
            float sw = 7f * s;
            gold.setStrokeWidth(sw);
            dim.setStrokeWidth(4f * s);

            Path ear = new Path();
            ear.moveTo(-2 * s, -42 * s);
            ear.cubicTo(-34 * s, -44 * s, -50 * s, -14 * s, -44 * s, 12 * s);
            ear.cubicTo(-38 * s, 38 * s, -12 * s, 47 * s, 10 * s, 39 * s);
            ear.cubicTo(30 * s, 32 * s, 39 * s, 11 * s, 32 * s, -8 * s);
            ear.cubicTo(26 * s, -25 * s, 10 * s, -29 * s, 5 * s, -13 * s);
            ear.cubicTo(1 * s, -1 * s, 16 * s, 4 * s, 16 * s, 17 * s);
            canvas.drawPath(ear, gold);

            // Inner sound wave
            Path wave = new Path();
            wave.moveTo(-20 * s, 6 * s);
            wave.lineTo(-12 * s, -2 * s);
            wave.lineTo(-4 * s, 12 * s);
            wave.lineTo(4 * s, -8 * s);
            wave.lineTo(12 * s, 6 * s);
            canvas.drawPath(wave, dim);
        }
    }

    /** Consistent line-icon set. Types are the I constants below. */
    static class IconView extends View {
        static final int HOME=1, SEARCH=2, LIBRARY=3, ACCOUNT=4, BELL=5, PLAY=6, PAUSE=7,
                HEART=8, HEART_FILLED=9, SHUFFLE=10, PREV=11, NEXT=12, REPEAT=13, MORE=14,
                ADD=15, EDIT=16, SETTINGS=17, PRIVACY=18, NOTIFICATIONS=19, HELP=20,
                LOGOUT=21, AUDIO=22, QUEUE=23, CHEVRON_RIGHT=24, CHEVRON_DOWN=25;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int type = HOME;
        private int color = Theme.TEXT_SEC;
        private boolean filled = false;

        IconView(Context c) { super(c); setWillNotDraw(false); }
        IconView type(int t) { type = t; invalidate(); return this; }
        IconView color(int col) { color = col; invalidate(); return this; }
        IconView filled(boolean f) { filled = f; invalidate(); return this; }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float s = Math.min(w, h) / 24f;
            canvas.translate(w / 2f, h / 2f);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.6f, 2f * s));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path q = new Path();
            switch (type) {
                case HOME:
                    q.moveTo(-10*s, -1*s); q.lineTo(0, -10*s); q.lineTo(10*s, -1*s);
                    q.moveTo(-8*s, -2*s); q.lineTo(-8*s, 10*s); q.lineTo(8*s, 10*s); q.lineTo(8*s, -2*s);
                    q.moveTo(-2*s, 10*s); q.lineTo(-2*s, 3*s); q.lineTo(2*s, 3*s); q.lineTo(2*s, 10*s);
                    canvas.drawPath(q, p); break;
                case SEARCH:
                    canvas.drawCircle(-2*s, -2*s, 6.5f*s, p);
                    q.moveTo(3*s, 3*s); q.lineTo(9*s, 9*s); canvas.drawPath(q, p); break;
                case LIBRARY:
                    canvas.drawRoundRect(new RectF(-10*s,-10*s,-5*s,10*s), 1.5f*s,1.5f*s,p);
                    canvas.drawRoundRect(new RectF(-2*s,-10*s,3*s,10*s), 1.5f*s,1.5f*s,p);
                    q.moveTo(8*s,-8*s); q.lineTo(8*s,9*s); q.lineTo(11*s,7*s); canvas.drawPath(q,p); break;
                case ACCOUNT:
                    canvas.drawCircle(0,-5*s,4.5f*s,p);
                    q.moveTo(-9*s,10*s); q.quadTo(0,0,9*s,10*s); canvas.drawPath(q,p); break;
                case BELL:
                    q.moveTo(-8*s,5*s); q.quadTo(-6*s,0,-6*s,-4*s); q.quadTo(0,-12*s,6*s,-4*s);
                    q.quadTo(6*s,0,8*s,5*s); q.close(); canvas.drawPath(q,p);
                    canvas.drawCircle(0,8*s,1.6f*s,p); break;
                case PLAY:
                    if (filled) { p.setStyle(Paint.Style.FILL); }
                    q.moveTo(-5*s,-8*s); q.lineTo(9*s,0); q.lineTo(-5*s,8*s); q.close();
                    canvas.drawPath(q,p); break;
                case PAUSE:
                    p.setStrokeWidth(4f*s);
                    q.moveTo(-4*s,-8*s); q.lineTo(-4*s,8*s);
                    q.moveTo(4*s,-8*s); q.lineTo(4*s,8*s); canvas.drawPath(q,p); break;
                case HEART:
                case HEART_FILLED:
                    if (type == HEART_FILLED) p.setStyle(Paint.Style.FILL);
                    q.moveTo(0,8*s);
                    q.cubicTo(-14*s,0,-9*s,-10*s,-4*s,-8*s);
                    q.cubicTo(-1*s,-7*s,0,-4*s,0,-4*s);
                    q.cubicTo(0,-4*s,1*s,-7*s,4*s,-8*s);
                    q.cubicTo(9*s,-10*s,14*s,0,0,8*s); q.close();
                    canvas.drawPath(q,p); break;
                case SHUFFLE:
                    q.moveTo(-10*s,-7*s); q.lineTo(-5*s,-7*s); q.quadTo(0,-7*s,3*s,0); q.lineTo(10*s,0);
                    q.moveTo(-10*s,7*s); q.lineTo(-5*s,7*s); q.quadTo(0,7*s,3*s,0);
                    q.moveTo(6*s,-3*s); q.lineTo(10*s,0); q.lineTo(6*s,3*s); canvas.drawPath(q,p); break;
                case PREV:
                    if (filled) p.setStyle(Paint.Style.FILL);
                    q.moveTo(-9*s,0); q.lineTo(6*s,-8*s); q.lineTo(6*s,8*s); q.close(); canvas.drawPath(q,p);
                    p.setStyle(Paint.Style.STROKE); q.reset();
                    q.moveTo(9*s,-8*s); q.lineTo(9*s,8*s); canvas.drawPath(q,p); break;
                case NEXT:
                    if (filled) p.setStyle(Paint.Style.FILL);
                    q.moveTo(9*s,0); q.lineTo(-6*s,-8*s); q.lineTo(-6*s,8*s); q.close(); canvas.drawPath(q,p);
                    p.setStyle(Paint.Style.STROKE); q.reset();
                    q.moveTo(-9*s,-8*s); q.lineTo(-9*s,8*s); canvas.drawPath(q,p); break;
                case REPEAT:
                    q.moveTo(-7*s,-4*s); q.quadTo(-10*s,0,-6*s,5*s); q.lineTo(7*s,5*s);
                    q.moveTo(4*s,2*s); q.lineTo(7*s,5*s); q.lineTo(4*s,8*s);
                    q.moveTo(7*s,4*s); q.quadTo(10*s,0,6*s,-5*s); q.lineTo(-7*s,-5*s);
                    q.moveTo(-4*s,-2*s); q.lineTo(-7*s,-5*s); q.lineTo(-4*s,-8*s); canvas.drawPath(q,p); break;
                case MORE:
                    p.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(-7*s,0,1.8f*s,p); canvas.drawCircle(0,0,1.8f*s,p);
                    canvas.drawCircle(7*s,0,1.8f*s,p); break;
                case ADD:
                    q.moveTo(-9*s,0); q.lineTo(9*s,0); q.moveTo(0,-9*s); q.lineTo(0,9*s); canvas.drawPath(q,p); break;
                case EDIT:
                    q.moveTo(-8*s,8*s); q.lineTo(-8*s,4*s); q.lineTo(4*s,-8*s); q.lineTo(8*s,-4*s);
                    q.lineTo(-4*s,8*s); q.close(); canvas.drawPath(q,p); break;
                case SETTINGS:
                    canvas.drawCircle(0,0,4*s,p);
                    for (int i=0;i<8;i++){ double a=i*Math.PI/4; float x1=(float)(7*Math.cos(a)*s), y1=(float)(7*Math.sin(a)*s);
                        float x2=(float)(10*Math.cos(a)*s), y2=(float)(10*Math.sin(a)*s);
                        q.moveTo(x1,y1); q.lineTo(x2,y2); }
                    canvas.drawPath(q,p); break;
                case PRIVACY:
                    q.moveTo(0,-10*s); q.lineTo(9*s,-6*s); q.lineTo(9*s,2*s); q.quadTo(0,11*s,-9*s,2*s);
                    q.lineTo(-9*s,-6*s); q.close(); canvas.drawPath(q,p);
                    canvas.drawCircle(0,0,2*s,p); break;
                case NOTIFICATIONS:
                    canvas.drawRoundRect(new RectF(-10*s,-7*s,10*s,7*s),2*s,2*s,p);
                    q.moveTo(-10*s,-2*s); q.lineTo(10*s,-2*s); canvas.drawPath(q,p); break;
                case HELP:
                    canvas.drawCircle(0,0,10*s,p);
                    q.moveTo(-3*s,-3*s); q.quadTo(0,-7*s,3*s,-3*s); q.quadTo(3*s,0,0,2*s);
                    q.moveTo(0,6*s); canvas.drawPath(q,p);
                    p.setStyle(Paint.Style.FILL); canvas.drawCircle(0,7*s,1.4f*s,p); break;
                case LOGOUT:
                    q.moveTo(-2*s,-9*s); q.lineTo(-7*s,-9*s); q.lineTo(-7*s,9*s); q.lineTo(-2*s,9*s);
                    q.moveTo(-2*s,0); q.lineTo(9*s,0); q.moveTo(5*s,-4*s); q.lineTo(9*s,0); q.lineTo(5*s,4*s);
                    canvas.drawPath(q,p); break;
                case AUDIO:
                    canvas.drawRoundRect(new RectF(-7*s,-5*s,7*s,5*s),2*s,2*s,p);
                    canvas.drawCircle(-3*s,3*s,1.6f*s,p); canvas.drawCircle(4*s,3*s,1.2f*s,p); break;
                case QUEUE:
                    q.moveTo(-9*s,-6*s); q.lineTo(9*s,-6*s); q.moveTo(-9*s,0); q.lineTo(6*s,0);
                    q.moveTo(-9*s,6*s); q.lineTo(3*s,6*s); canvas.drawPath(q,p); break;
                case CHEVRON_RIGHT:
                    q.moveTo(-4*s,-8*s); q.lineTo(4*s,0); q.lineTo(-4*s,8*s); canvas.drawPath(q,p); break;
                case CHEVRON_DOWN:
                    q.moveTo(-8*s,-4*s); q.lineTo(0,4*s); q.lineTo(8*s,-4*s); canvas.drawPath(q,p); break;
            }
        }
    }

    /** Circular avatar with a gold ring and an initial. */
    static class AvatarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String initial = "T";
        AvatarView(Context c) {
            super(c); setWillNotDraw(false);
            text.setColor(Theme.GOLD_LIGHT);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Theme.dp(c, 20));
            text.setStyle(Paint.Style.FILL);
        }
        AvatarView initial(String s) { initial = s.isEmpty() ? "T" : s.substring(0,1).toUpperCase(); invalidate(); return this; }
        @Override protected void onDraw(Canvas canvas) {
            int w=getWidth(), h=getHeight(); float r=Math.min(w,h)/2f;
            float cx=w/2f, cy=h/2f;
            p.setShader(new LinearGradient(0,0,0,h,Theme.ELEVATED,Theme.SURFACE,Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL); canvas.drawCircle(cx,cy,r-2,p);
            p.setShader(null); p.setColor(Theme.GOLD); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f,r*0.06f)); canvas.drawCircle(cx,cy,r-2,p);
            text.setTextSize(r*0.9f);
            Paint.FontMetrics fm=text.getFontMetrics();
            canvas.drawText(initial,cx,cy-(fm.ascent+fm.descent)/2,text);
        }
    }

    /** Draggable gold progress bar. */
    static class SeekBarView extends View {
        interface Listener { void onSeek(float progress); }
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress = 0.35f;
        private Listener listener;
        SeekBarView(Context c) { super(c); setWillNotDraw(false); }
        SeekBarView listener(Listener l) { listener = l; return this; }
        SeekBarView progress(float v) { progress = Math.max(0,Math.min(1,v)); invalidate(); return this; }
        float progress() { return progress; }
        @Override protected void onDraw(Canvas canvas) {
            int w=getWidth(), h=getHeight(); float y=h/2f;
            float t=Theme.dp(getContext(),4);
            p.setColor(Theme.ELEVATED); p.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(0,y-t/2,w,y+t/2),t/2,t/2,p);
            int ww=Math.max((int)(w*progress),0);
            p.setShader(new LinearGradient(0,0,ww,0,Theme.GOLD_DEEP,Theme.GOLD_LIGHT,Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(0,y-t/2,ww,y+t/2),t/2,t/2,p);
            p.setShader(null); p.setColor(Theme.GOLD_LIGHT);
            p.setShadowLayer(Theme.dp(getContext(),8),0,0,0x88D4AF37);
            canvas.drawCircle(ww,y,Theme.dp(getContext(),8),p);
            p.setShadowLayer(0,0,0,0);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (getWidth() < 2) return true;
            float x=e.getX();
            progress=Math.max(0,Math.min(1,x/getWidth()));
            invalidate();
            if(listener!=null) listener.onSeek(progress);
            if(e.getAction()==MotionEvent.ACTION_DOWN) return true;
            performClick(); return true;
        }
        @Override public boolean performClick(){ return super.performClick(); }
    }
}
