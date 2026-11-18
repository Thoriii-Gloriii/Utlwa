package com.buddyapps.utlwa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Utlwa! — black-and-gold music UI powered by NewPipe's real extractor backend. */
public class MainActivity extends Activity {

    private static MainActivity instance;
    private static final String CHANNEL = "utlwa_playback";
    private static final int NOTIF_ID = 1001;

    private FrameLayout root, screenBox;
    private View splash, main, player, mini;
    private LinearLayout nav;
    private Views.IconView[] navIcons = new Views.IconView[4];
    private TextView[] navLabels = new TextView[4];
    private int tab = 0;

    // Real data from NewPipeExtractor
    private List<Backend.Track> charts = new ArrayList<>();
    private List<Backend.Track> made = new ArrayList<>();
    private List<Backend.Track> results = new ArrayList<>();
    private boolean chartsLoaded = false, madeLoaded = false, searching = false;
    private String diag = "";
    private int chartAttempts = 0;
    /** Survives showScreen(1) rebuilds so the search box keeps its text. */
    private String currentQuery = "";

    // Player
    private MediaPlayer mp;
    private int trackMs = 0;
    private List<String> audioUrls = new ArrayList<>();
    private int audioIdx = 0;
    private Backend.Track current;
    private List<Backend.Track> queue = new ArrayList<>();
    private boolean playing = false, shuffle = false, repeat = false;
    private int positionBeforeError = 0; // saved on error so seekTo restores position
    private BroadcastReceiver noisyReceiver; // pauses on headphone unplug
    private Views.ArtworkView artBig, artMini;
    private Views.SeekBarView seek;
    private TextView songTitle, songArtist, miniTitle, miniArtist, timeNow, timeEnd;
    private Views.IconView heartIcon, shuffleIcon, repeatIcon, playIcon, miniPlayIcon;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (playing && mp != null && current != null) {
                try {
                    int pos = mp.getCurrentPosition();
                    int dur = durationMs();
                    seek.progress(dur > 0 ? pos / (float) dur : 0f);
                    timeNow.setText(fmt(pos / 1000));
                    // Completion is handled by OnCompletionListener; tick only updates UI.
                } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 400);
        }
    };

    private int durationMs() {
        if (mp != null) {
            try { int d = mp.getDuration(); if (d > 0) return d; } catch (Exception ignored) {}
        }
        return current == null ? 0 : (int) current.duration * 1000;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        Backend.init(this);
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer m, int what, int extra) {
                playing = false; updatePlayIcons();
                // Save position so we can seek back after switching to a fallback source.
                try { positionBeforeError = m.getCurrentPosition(); } catch (Exception ignored) {}
                playAudioAt(audioIdx + 1, what, extra);
                return true;
            }
        });
        // Advance or repeat when a track finishes naturally — no polling needed.
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer m) {
                if (repeat) {
                    try { m.seekTo(0); m.start(); } catch (Exception ignored) {}
                } else {
                    playing = false; nextTrack();
                }
            }
        });
        // Pause when headphones are unplugged.
        noisyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()) && playing) {
                    try { mp.pause(); playing = false; updatePlayIcons(); updateNotification(); }
                    catch (Exception ignored) {}
                }
            }
        };
        registerReceiver(noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);
        splash = buildSplash(); root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        main = buildMain(); root.addView(main, new FrameLayout.LayoutParams(-1, -1)); main.setVisibility(View.GONE);
        player = buildPlayer(); root.addView(player, new FrameLayout.LayoutParams(-1, -1)); player.setVisibility(View.GONE);
        setContentView(root);

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                splash.setVisibility(View.GONE);
                main.setVisibility(View.VISIBLE);
                main.startAnimation(Theme.fadeIn(320));
                showScreen(0);
            }
        }, 2200);
        handler.post(tick);
        loadCharts();
    }

    /* ---------------- real data loading ---------------- */
    private void loadCharts() {
        Backend.musicKiosk(new Backend.Callback<List<Backend.Track>>() {
            @Override public void result(List<Backend.Track> v, Exception e) {
                if (v != null && !v.isEmpty()) {
                    charts = v; chartsLoaded = true;
                    // Only seed the queue from charts when nothing is playing; otherwise
                    // mid-song "next" would jump to a chart track the user didn't choose.
                    if (current == null) queue = new ArrayList<>(charts);
                    if (tab == 0 || tab == 1) showScreen(tab);
                    if (!madeLoaded) loadMade();
                } else {
                    diag = e == null ? Backend.lastError : e.toString();
                    if (chartAttempts < 2) {
                        chartAttempts++;
                        handler.postDelayed(new Runnable() {
                            @Override public void run() { loadCharts(); }
                        }, 2500);
                    } else if (tab == 0) showScreen(0);
                }
            }
        });
    }

    private void loadMade() {
        if (charts.isEmpty()) return;
        Backend.related(charts.get(0), new Backend.Callback<List<Backend.Track>>() {
            @Override public void result(List<Backend.Track> v, Exception e) {
                madeLoaded = true;
                if (v != null && !v.isEmpty()) made = v;
                else made = new ArrayList<>(charts);
                if (tab == 0) showScreen(0);
            }
        });
    }

    /* ---------------- splash ---------------- */
    private View buildSplash() {
        FrameLayout s = new FrameLayout(this);
        s.setBackgroundColor(Theme.BG);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.utlwa_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Theme.dp(this, 150), Theme.dp(this, 150));
        lp.gravity = Gravity.CENTER; lp.topMargin = -Theme.dp(this, 40);
        s.addView(logo, lp);
        LinearLayout bottom = ll(this, LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        Views.GoldTextView name = new Views.GoldTextView(this);
        name.setText("Utlwa!"); name.setTextSize(Theme.sp(this, 30)); name.setTypeface(null, 1);
        name.setLetterSpacing(0.08f); bottom.addView(name);
        TextView tag = new TextView(this);
        tag.setText("MUSIC, REDEFINED."); tag.setTextColor(Theme.MUTED);
        tag.setTextSize(Theme.sp(this, 11)); tag.setLetterSpacing(0.22f);
        tag.setPadding(0, Theme.dp(this, 6), 0, 0); bottom.addView(tag);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2);
        bl.gravity = Gravity.CENTER; bl.topMargin = Theme.dp(this, 62); s.addView(bottom, bl);
        View bar = new View(this); bar.setBackground(Theme.goldGradient(3, this));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Theme.dp(this, 140), Theme.dp(this, 3));
        bp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; bp.bottomMargin = Theme.dp(this, 58);
        s.addView(bar, bp);
        android.view.animation.AlphaAnimation pulse = new android.view.animation.AlphaAnimation(0.25f, 1f);
        pulse.setDuration(900); pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
        pulse.setRepeatCount(android.view.animation.Animation.INFINITE); bar.startAnimation(pulse);
        return s;
    }

    /* ---------------- shell ---------------- */
    private View buildMain() {
        LinearLayout m = ll(this, LinearLayout.VERTICAL); m.setBackgroundColor(Theme.BG);
        screenBox = new FrameLayout(this);
        screenBox.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1)); m.addView(screenBox);
        mini = buildMini(); mini.setVisibility(View.GONE);
        m.addView(mini, new LinearLayout.LayoutParams(-1, Theme.dp(this, 60)));
        View divider = new View(this); divider.setBackgroundColor(Theme.BORDER);
        m.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(Theme.SURFACE);
        nav.setPadding(0, Theme.dp(this, 6), 0, Theme.dp(this, 6));
        int[] types = {Views.IconView.HOME, Views.IconView.SEARCH, Views.IconView.LIBRARY, Views.IconView.ACCOUNT};
        String[] labels = {"Home", "Search", "Your Library", "Account"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = ll(this, LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
            Views.IconView ic = new Views.IconView(this).type(types[i]);
            ic.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 24), Theme.dp(this, 24)));
            TextView lb = new TextView(this); lb.setText(labels[i]); lb.setTextSize(Theme.sp(this, 10));
            lb.setGravity(Gravity.CENTER); lb.setPadding(0, Theme.dp(this, 3), 0, 0);
            item.addView(ic); item.addView(lb);
            navIcons[i] = ic; navLabels[i] = lb;
            item.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showScreen(idx); } });
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        m.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        return m;
    }

    private void showScreen(int i) {
        tab = i;
        screenBox.removeAllViews();
        View v;
        switch (i) {
            case 1: v = buildSearch(); break;
            case 2: v = buildLibrary(); break;
            case 3: v = buildAccount(); break;
            default: v = buildHome(); break;
        }
        screenBox.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.startAnimation(Theme.fadeIn(160));
        for (int k = 0; k < 4; k++) {
            boolean on = k == i;
            navIcons[k].color(on ? Theme.GOLD_LIGHT : Theme.MUTED);
            navLabels[k].setTextColor(on ? Theme.GOLD : Theme.TEXT_SEC);
        }
    }

    /* ---------------- home ---------------- */
    private View buildHome() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Theme.BG);
        LinearLayout c = ll(this, LinearLayout.VERTICAL);
        c.setPadding(Theme.dp(this, 20), Theme.dp(this, 18), Theme.dp(this, 20), Theme.dp(this, 28));

        LinearLayout head = ll(this, LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandLogo = new ImageView(this);
        brandLogo.setImageResource(R.drawable.utlwa_logo);
        brandLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int bl = Theme.dp(this, 32);
        brandLogo.setLayoutParams(new LinearLayout.LayoutParams(bl, bl));
        head.addView(brandLogo);
        Views.GoldTextView brand = new Views.GoldTextView(this);
        brand.setText("Utlwa!"); brand.setTextSize(Theme.sp(this, 21)); brand.setTypeface(null, 1);
        brand.setPadding(Theme.dp(this, 6), 0, 0, 0);
        head.addView(brand, new LinearLayout.LayoutParams(-2, -2, 1));
        Views.IconView bell = new Views.IconView(this).type(Views.IconView.BELL).color(Theme.WHITE);
        bell.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 23), Theme.dp(this, 23)));
        bell.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toast("No new notifications"); } });
        head.addView(bell);
        Views.AvatarView av = new Views.AvatarView(this).initial("T");
        LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(Theme.dp(this, 32), Theme.dp(this, 32));
        avp.leftMargin = Theme.dp(this, 14); av.setLayoutParams(avp);
        av.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showScreen(3); } });
        head.addView(av); c.addView(head);

        LinearLayout sb = ll(this, LinearLayout.HORIZONTAL);
        sb.setBackground(Theme.stroked(Theme.CARD, Theme.BORDER, 1, 12, this));
        sb.setPadding(Theme.dp(this, 14), Theme.dp(this, 12), Theme.dp(this, 14), Theme.dp(this, 12));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2); sbp.topMargin = Theme.dp(this, 22);
        Views.IconView si = new Views.IconView(this).type(Views.IconView.SEARCH).color(Theme.MUTED);
        si.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 20), Theme.dp(this, 20))); sb.addView(si);
        TextView sh = new TextView(this); sh.setText("Search music..."); sh.setTextColor(Theme.MUTED);
        sh.setTextSize(Theme.sp(this, 14)); sh.setPadding(Theme.dp(this, 12), 0, 0, 0); sb.addView(sh);
        sb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showScreen(1); } });
        c.addView(sb, sbp);

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greet = hour < 12 ? "Good Morning!" : hour < 17 ? "Good Afternoon!" : "Good Evening!";
        FrameLayout banner = new FrameLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Theme.dp(this, 148)); bp.topMargin = Theme.dp(this, 18);
        banner.setBackground(Theme.rounded(Theme.CARD, 16, this));
        Views.ArtworkView bart = new Views.ArtworkView(this).seed(61).corner(16);
        if (!charts.isEmpty()) bart.url(charts.get(0).thumb);
        banner.addView(bart, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xEE050505, 0x88050505, 0x11050505}));
        banner.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearText bt = new LinearText(this, greet, "Let's vibe with Utlwa!", true);
        FrameLayout.LayoutParams btp = new FrameLayout.LayoutParams(-2, -2);
        btp.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT; btp.leftMargin = Theme.dp(this, 18);
        banner.addView(bt, btp);
        Views.IconView bplay = new Views.IconView(this).type(Views.IconView.PLAY).color(Theme.BG).filled(true);
        bplay.setBackground(Theme.goldGradient(100, this));
        FrameLayout.LayoutParams bpp = new FrameLayout.LayoutParams(Theme.dp(this, 52), Theme.dp(this, 52));
        bpp.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT; bpp.rightMargin = Theme.dp(this, 18);
        banner.addView(bplay, bpp);
        bplay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!charts.isEmpty()) { queue = new ArrayList<>(charts); playTrack(charts.get(0)); }
                else toast("Loading music…");
            }
        });
        c.addView(banner, bp);

        c.addView(section("Recently Played"));
        List<Backend.Track> rec = Backend.recent();
        if (rec.isEmpty()) c.addView(empty("Nothing played yet. Tap a song below to start."));
        else {
            HorizontalCards hr = new HorizontalCards();
            for (Backend.Track t : rec) hr.add(trackCard(t, false));
            c.addView(hr.view(this));
        }

        c.addView(section("Made For You"));
        if (made.isEmpty()) c.addView(empty(chartsLoaded ? "Loading recommendations…" :
                (diag.isEmpty() ? "Loading music…" : diag)));
        else {
            HorizontalCards hm = new HorizontalCards();
            for (int i = 0; i < Math.min(8, made.size()); i++) hm.add(trackCard(made.get(i), true));
            c.addView(hm.view(this));
        }

        c.addView(section("Trending Now"));
        if (!chartsLoaded) c.addView(empty(diag.isEmpty() ? "Loading real charts from YouTube…" : diag));
        else for (int i = 0; i < Math.min(8, charts.size()); i++) c.addView(trackRow(charts.get(i), i + 1));

        sv.addView(c, new ScrollView.LayoutParams(-1, -2)); return sv;
    }

    private View empty(String text) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(Theme.MUTED); t.setTextSize(Theme.sp(this, 13));
        t.setPadding(0, Theme.dp(this, 10), 0, Theme.dp(this, 10));
        return withMargins(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private static class LinearText extends LinearLayout {
        LinearText(android.content.Context c, String title, String sub, boolean goldTitle) {
            super(c); setOrientation(VERTICAL);
            TextView t = new TextView(c); t.setText(title); t.setTextSize(Theme.sp(c, 20));
            t.setTypeface(null, 1); t.setTextColor(goldTitle ? Theme.GOLD_LIGHT : Theme.WHITE); addView(t);
            TextView s = new TextView(c); s.setText(sub); s.setTextSize(Theme.sp(c, 13));
            s.setTextColor(Theme.TEXT_SEC); s.setPadding(0, Theme.dp(c, 4), 0, 0); addView(s);
        }
    }

    private View section(String title) {
        LinearLayout r = ll(this, LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this); t.setText(title); t.setTextColor(Theme.WHITE);
        t.setTextSize(Theme.sp(this, 17)); t.setTypeface(null, 1);
        r.addView(t, new LinearLayout.LayoutParams(-2, -2, 1));
        TextView see = new TextView(this); see.setText("See all"); see.setTextColor(Theme.GOLD);
        see.setTextSize(Theme.sp(this, 12)); r.addView(see);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Theme.dp(this, 20); rp.bottomMargin = Theme.dp(this, 10);
        LinearLayout w = ll(this, LinearLayout.VERTICAL); w.addView(r, rp); return w;
    }

    private class HorizontalCards {
        private final List<View> items = new ArrayList<>();
        void add(View v) { items.add(v); }
        View view(android.content.Context c) {
            HorizontalScrollView hs = new HorizontalScrollView(c); hs.setHorizontalScrollBarEnabled(false);
            LinearLayout r = ll(c, LinearLayout.HORIZONTAL); r.setPadding(0, 0, Theme.dp(c, 4), 0);
            for (View v : items) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
                p.rightMargin = Theme.dp(c, 12); r.addView(v, p);
            }
            hs.addView(r, new HorizontalScrollView.LayoutParams(-2, -2)); return hs;
        }
    }

    private View trackCard(final Backend.Track t, boolean large) {
        int maxTwo = (getResources().getDisplayMetrics().widthPixels - Theme.dp(this, 70)) / 2;
        int size = Math.min(Theme.dp(this, large ? 158 : 122), maxTwo);
        LinearLayout v = ll(this, LinearLayout.VERTICAL);
        v.setBackground(Theme.rounded(Theme.CARD, 12, this));
        v.setPadding(Theme.dp(this, 8), Theme.dp(this, 8), Theme.dp(this, 8), Theme.dp(this, 12));
        Views.ArtworkView a = new Views.ArtworkView(this).seed(t.url.hashCode()).corner(10).url(t.thumb);
        a.setLayoutParams(new LinearLayout.LayoutParams(size, size)); v.addView(a);
        TextView n = new TextView(this); n.setText(t.title); n.setTextColor(Theme.WHITE);
        n.setTextSize(Theme.sp(this, 13)); n.setTypeface(null, 1); n.setSingleLine(true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(size, -2); np.topMargin = Theme.dp(this, 10);
        v.addView(n, np);
        TextView s = new TextView(this); s.setText(t.artist); s.setTextColor(Theme.MUTED);
        s.setTextSize(Theme.sp(this, 11)); s.setSingleLine(true); v.addView(s, new LinearLayout.LayoutParams(size, -2));
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) {
            if (!queueContains(t)) queue.add(t); playTrack(t);
        }});
        return v;
    }

    private View trackRow(final Backend.Track t, final int rank) {
        LinearLayout r = ll(this, LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = new TextView(this); n.setText(String.valueOf(rank)); n.setTextColor(Theme.GOLD);
        n.setTextSize(Theme.sp(this, 15)); n.setGravity(Gravity.CENTER);
        n.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 24), -2)); r.addView(n);
        Views.ArtworkView a = new Views.ArtworkView(this).seed(t.url.hashCode()).corner(7).url(t.thumb);
        int as = Theme.dp(this, 44); a.setLayoutParams(new LinearLayout.LayoutParams(as, as)); r.addView(a);
        LinearLayout tx = ll(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1); tp.leftMargin = Theme.dp(this, 12);
        TextView ti = new TextView(this); ti.setText(t.title); ti.setTextColor(Theme.WHITE);
        ti.setTextSize(Theme.sp(this, 13)); ti.setTypeface(null, 1); ti.setSingleLine(true);
        TextView ar = new TextView(this); ar.setText(t.artist); ar.setTextColor(Theme.TEXT_SEC);
        ar.setTextSize(Theme.sp(this, 11)); ar.setSingleLine(true);
        tx.addView(ti); tx.addView(ar); r.addView(tx, tp);
        Views.IconView more = new Views.IconView(this).type(Views.IconView.MORE).color(Theme.MUTED);
        more.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 24), Theme.dp(this, 24)));
        more.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            Backend.toggleLiked(t); toast(Backend.isLiked(t) ? "Added to Your Library" : "Removed from Your Library");
        }});
        r.addView(more);
        r.setPadding(0, Theme.dp(this, 8), 0, Theme.dp(this, 8));
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (!queueContains(t)) queue.add(t); playTrack(t);
        }});
        return withMargins(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private View withMargins(View v, LinearLayout.LayoutParams p) {
        LinearLayout w = ll(this, LinearLayout.VERTICAL); w.addView(v, p); return w;
    }

    /* ---------------- search (real) ---------------- */
    private View buildSearch() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Theme.BG);
        final LinearLayout c = ll(this, LinearLayout.VERTICAL);
        c.setPadding(Theme.dp(this, 20), Theme.dp(this, 18), Theme.dp(this, 20), Theme.dp(this, 28));
        final EditText e = new EditText(this);
        e.setHint("Search artists, songs, or playlists..."); e.setHintTextColor(Theme.MUTED);
        e.setTextColor(Theme.WHITE); e.setSingleLine(true);
        e.setBackground(Theme.stroked(Theme.CARD, Theme.BORDER, 1, 12, this));
        e.setPadding(Theme.dp(this, 16), Theme.dp(this, 13), Theme.dp(this, 13), Theme.dp(this, 13));
        c.addView(e, new LinearLayout.LayoutParams(-1, -2));
        // Restore query so it isn't lost when showScreen(1) rebuilds the view.
        if (!currentQuery.isEmpty()) {
            e.setText(currentQuery);
            e.setSelection(currentQuery.length());
        }

        final LinearLayout box = ll(this, LinearLayout.VERTICAL);
        c.addView(box, new LinearLayout.LayoutParams(-1, -2));
        if (results.isEmpty() && searching) box.addView(empty("Searching YouTube…"));
        else if (!results.isEmpty()) renderResults(box);
        else { box.addView(browseView()); box.addView(chartsView()); }

        final Handler debounce = new Handler();
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(final CharSequence q, int a, int b, int d) {
                debounce.removeCallbacksAndMessages(null);
                debounce.postDelayed(new Runnable() {
                    @Override public void run() {
                        final String query = q.toString().trim();
                        currentQuery = query;
                        if (query.isEmpty()) {
                            results.clear();
                            box.removeAllViews(); box.addView(browseView()); box.addView(chartsView());
                            return;
                        }
                        searching = true; box.removeAllViews(); box.addView(empty("Searching YouTube…"));
                        Backend.search(query, new Backend.Callback<List<Backend.Track>>() {
                            @Override public void result(List<Backend.Track> v, Exception err) {
                                searching = false;
                                results = v != null && !v.isEmpty() ? v : new ArrayList<Backend.Track>();
                                box.removeAllViews();
                                if (err != null && results.isEmpty()) {
                                    box.addView(empty("Search failed — check your connection."));
                                } else {
                                    renderResults(box);
                                }
                            }
                        });
                    }
                }, 450);
            }
        });
        sv.addView(c, new ScrollView.LayoutParams(-1, -2)); return sv;
    }

    private void renderResults(LinearLayout box) {
        TextView h = new TextView(this); h.setText("Top Results"); h.setTextColor(Theme.GOLD);
        h.setTextSize(Theme.sp(this, 15)); h.setTypeface(null, 1);
        h.setPadding(0, Theme.dp(this, 20), 0, Theme.dp(this, 10)); box.addView(h);
        if (results.isEmpty()) box.addView(empty("No results found — try another search."));
        else { queue = new ArrayList<>(results); int i = 1; for (Backend.Track t : results) box.addView(trackRow(t, i++)); }
    }

    private View browseView() {
        LinearLayout w = ll(this, LinearLayout.VERTICAL); w.addView(section("Browse Categories"));
        String[] genres = {"Afrobeats", "Hip-Hop", "Pop", "Chill", "Rock", "Electronic", "R&B", "Gospel"};
        // Three-column grid matches the approved Utlwa! discovery design.
        for (int i = 0; i < genres.length; i += 3) {
            LinearLayout row = ll(this, LinearLayout.HORIZONTAL);
            for (int j = 0; j < 3 && i + j < genres.length; j++) {
                final String g = genres[i + j];
                View card = genreCard(g, i + j);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Theme.dp(this, 112), 1);
                p.leftMargin = j > 0 ? Theme.dp(this, 4) : 0;
                p.rightMargin = j < 2 ? Theme.dp(this, 4) : 0;
                p.bottomMargin = Theme.dp(this, 8);
                card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    // launch a real search for the genre
                    currentQuery = g;
                    searching = true;
                    Backend.search(g, new Backend.Callback<List<Backend.Track>>() {
                        @Override public void result(List<Backend.Track> val, Exception er) {
                            searching = false;
                            results = val != null && !val.isEmpty() ? val : new ArrayList<Backend.Track>();
                            if (tab == 1) showScreen(1);
                            else if (er != null && results.isEmpty()) toast("Search failed for \"" + g + "\"");
                        }
                    });
                    // Switch to Search tab so the user sees the spinner/results.
                    showScreen(1);
                }});
                row.addView(card, p);
            }
            row.setWeightSum(3f);
            w.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        return w;
    }

    private View genreCard(String name, int seed) {
        FrameLayout f = new FrameLayout(this); f.setBackground(Theme.rounded(Theme.CARD, 12, this));
        f.addView(new Views.ArtworkView(this).seed(seed * 13 + 3).corner(12), new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC050505, 0x22050505}));
        f.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        TextView t = new TextView(this); t.setText(name); t.setTextColor(Theme.WHITE);
        t.setTextSize(Theme.sp(this, 14)); t.setTypeface(null, 1);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.BOTTOM | Gravity.LEFT; tp.leftMargin = Theme.dp(this, 12);
        tp.bottomMargin = Theme.dp(this, 12); f.addView(t, tp); return f;
    }

    private View chartsView() {
        LinearLayout w = ll(this, LinearLayout.VERTICAL); w.addView(section("Trending Now"));
        if (!chartsLoaded) w.addView(empty("Loading real charts…"));
        else for (int i = 0; i < Math.min(6, charts.size()); i++) w.addView(trackRow(charts.get(i), i + 1));
        return w;
    }

    /* ---------------- library (real local data) ---------------- */
    private int libFilter = 0;
    private TextView[] chips = new TextView[4];
    private LinearLayout libList;

    private View buildLibrary() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Theme.BG);
        LinearLayout c = ll(this, LinearLayout.VERTICAL);
        c.setPadding(Theme.dp(this, 20), Theme.dp(this, 18), Theme.dp(this, 20), Theme.dp(this, 28));
        LinearLayout h = ll(this, LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this); t.setText("Your Library"); t.setTextColor(Theme.WHITE);
        t.setTextSize(Theme.sp(this, 24)); t.setTypeface(null, 1);
        h.addView(t, new LinearLayout.LayoutParams(-2, -2, 1));
        Views.IconView s = new Views.IconView(this).type(Views.IconView.SEARCH).color(Theme.WHITE);
        s.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 22), Theme.dp(this, 22)));
        s.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showScreen(1); } });
        h.addView(s);
        Views.IconView add = new Views.IconView(this).type(Views.IconView.ADD).color(Theme.GOLD);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Theme.dp(this, 24), Theme.dp(this, 24));
        ap.leftMargin = Theme.dp(this, 16); add.setLayoutParams(ap);
        add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { createPlaylist(); } });
        h.addView(add); c.addView(h);

        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout cr = ll(this, LinearLayout.HORIZONTAL);
        String[] names = {"Playlists", "Albums", "Artists", "Downloads"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView chip = new TextView(this); chip.setText(names[i]); chip.setTextSize(Theme.sp(this, 12));
            chip.setPadding(Theme.dp(this, 16), Theme.dp(this, 8), Theme.dp(this, 16), Theme.dp(this, 8));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.rightMargin = Theme.dp(this, 8); chip.setLayoutParams(cp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { libFilter = idx; showScreen(2); }
            });
            chips[i] = chip; cr.addView(chip);
        }
        hs.addView(cr, new HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.topMargin = Theme.dp(this, 20);
        c.addView(hs, hp);

        libList = ll(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = Theme.dp(this, 12);
        c.addView(libList, lp);
        renderLibrary();
        sv.addView(c, new ScrollView.LayoutParams(-1, -2)); return sv;
    }

    private void renderLibrary() {
        libList.removeAllViews();
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == libFilter;
            chips[i].setBackground(on ? Theme.goldGradient(20, this)
                    : Theme.stroked(Theme.CARD, Theme.BORDER, 1, 20, this));
            chips[i].setTextColor(on ? Theme.BG : Theme.TEXT_SEC);
        }
        if (libFilter == 0) {
            List<String> pls = Backend.playlists();
            libList.addView(empty("Your playlists"));
            for (String name : pls) libList.addView(playlistItem(name));
            libList.addView(empty("Saved songs"));
            List<Backend.Track> liked = Backend.liked();
            if (liked.isEmpty()) libList.addView(empty("No saved songs yet. Tap ⋮ on a song to save it."));
            else for (Backend.Track t : liked) libList.addView(savedItem(t));
        } else {
            String msg = libFilter == 1 ? "No albums saved yet." : libFilter == 2 ? "No artists followed yet." : "No downloads yet.";
            libList.addView(empty(msg));
        }
    }

    private View playlistItem(final String name) {
        LinearLayout r = ll(this, LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Theme.dp(this, 9), 0, Theme.dp(this, 9));
        Views.ArtworkView a = new Views.ArtworkView(this).seed(name.hashCode()).corner(8);
        int s = Theme.dp(this, 56); a.setLayoutParams(new LinearLayout.LayoutParams(s, s)); r.addView(a);
        LinearLayout tx = ll(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1); tp.leftMargin = Theme.dp(this, 14);
        TextView n = new TextView(this); n.setText(name); n.setTextColor(Theme.WHITE);
        n.setTextSize(Theme.sp(this, 14)); n.setTypeface(null, 1);
        TextView d = new TextView(this); d.setText("Your playlist"); d.setTextColor(Theme.MUTED);
        d.setTextSize(Theme.sp(this, 11)); d.setPadding(0, Theme.dp(this, 3), 0, 0);
        tx.addView(n); tx.addView(d); r.addView(tx, tp);
        return r;
    }

    private View savedItem(final Backend.Track t) {
        LinearLayout r = ll(this, LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Theme.dp(this, 8), 0, Theme.dp(this, 8));
        Views.ArtworkView a = new Views.ArtworkView(this).seed(t.url.hashCode()).corner(8).url(t.thumb);
        int s = Theme.dp(this, 52); a.setLayoutParams(new LinearLayout.LayoutParams(s, s)); r.addView(a);
        LinearLayout tx = ll(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1); tp.leftMargin = Theme.dp(this, 12);
        TextView n = new TextView(this); n.setText(t.title); n.setTextColor(Theme.WHITE);
        n.setTextSize(Theme.sp(this, 13)); n.setTypeface(null, 1); n.setSingleLine(true);
        TextView d = new TextView(this); d.setText(t.artist); d.setTextColor(Theme.MUTED);
        d.setTextSize(Theme.sp(this, 11)); d.setSingleLine(true);
        tx.addView(n); tx.addView(d); r.addView(tx, tp);
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { playTrack(t); } });
        return r;
    }

    private void createPlaylist() {
        final EditText input = new EditText(this); input.setHint("Playlist name");
        input.setTextColor(Theme.WHITE); input.setHintTextColor(Theme.MUTED); input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Create Playlist").setView(input)
                .setPositiveButton("Create", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String n = input.getText().toString().trim();
                        if (n.isEmpty()) return;
                        Backend.addPlaylist(n); showScreen(2); toast("Created " + n);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /* ---------------- account ---------------- */
    private String displayName = "Thori TheGlory", handle = "@thoritheglory";

    private View buildAccount() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Theme.BG);
        LinearLayout c = ll(this, LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(Theme.dp(this, 20), Theme.dp(this, 28), Theme.dp(this, 20), Theme.dp(this, 36));
        Views.AvatarView av = new Views.AvatarView(this).initial("T");
        av.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 96), Theme.dp(this, 96))); c.addView(av);
        TextView n = new TextView(this); n.setText(displayName); n.setTextColor(Theme.WHITE);
        n.setTextSize(Theme.sp(this, 20)); n.setTypeface(null, 1); n.setPadding(0, Theme.dp(this, 14), 0, 0); c.addView(n);
        TextView u = new TextView(this); u.setText(handle); u.setTextColor(Theme.MUTED);
        u.setTextSize(Theme.sp(this, 13)); c.addView(u);
        TextView edit = new TextView(this); edit.setText("Edit Profile"); edit.setTextColor(Theme.GOLD);
        edit.setTextSize(Theme.sp(this, 12));
        edit.setPadding(Theme.dp(this, 22), Theme.dp(this, 8), Theme.dp(this, 22), Theme.dp(this, 8));
        edit.setBackground(Theme.stroked(Theme.BG, Theme.GOLD, 1, 20, this));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2); ep.topMargin = Theme.dp(this, 14);
        edit.setLayoutParams(ep);
        edit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final EditText a = new EditText(MainActivity.this); a.setText(displayName); a.setTextColor(Theme.WHITE); a.setSingleLine(true);
            final EditText b = new EditText(MainActivity.this); b.setText(handle); b.setTextColor(Theme.WHITE); b.setSingleLine(true);
            LinearLayout box = ll(MainActivity.this, LinearLayout.VERTICAL);
            box.setPadding(Theme.dp(MainActivity.this, 20), Theme.dp(MainActivity.this, 10), Theme.dp(MainActivity.this, 20), 0);
            box.addView(a); box.addView(b);
            new AlertDialog.Builder(MainActivity.this).setTitle("Edit Profile").setView(box)
                    .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            if (!a.getText().toString().trim().isEmpty()) displayName = a.getText().toString().trim();
                            if (!b.getText().toString().trim().isEmpty()) handle = b.getText().toString().trim();
                            showScreen(3);
                        }
                    }).setNegativeButton("Cancel", null).show();
        }});
        c.addView(edit);

        LinearLayout panel = ll(this, LinearLayout.VERTICAL); panel.setBackground(Theme.rounded(Theme.CARD, 16, this));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = Theme.dp(this, 24);
        setting(panel, "Account", "Manage your account information", Views.IconView.ACCOUNT);
        setting(panel, "Playback", "Music playback preferences", Views.IconView.PLAY);
        setting(panel, "Notifications", "Notification preferences", Views.IconView.NOTIFICATIONS);
        setting(panel, "Privacy & Security", "Privacy and account protection", Views.IconView.PRIVACY);
        setting(panel, "Appearance", "Dark Mode", Views.IconView.SETTINGS);
        setting(panel, "Help & Support", "Help, docs and contact", Views.IconView.HELP);
        c.addView(panel, pp);

        TextView out = new TextView(this); out.setText("Log Out"); out.setTextColor(Theme.GOLD);
        out.setTextSize(Theme.sp(this, 14)); out.setGravity(Gravity.CENTER);
        out.setPadding(0, Theme.dp(this, 14), 0, Theme.dp(this, 14));
        out.setBackground(Theme.stroked(Theme.BG, Theme.GOLD, 1, 12, this));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2); op.topMargin = Theme.dp(this, 26);
        out.setLayoutParams(op);
        out.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            new AlertDialog.Builder(MainActivity.this).setTitle("Log out of Utlwa?")
                    .setMessage("This is a local demo — no account session is stored.")
                    .setPositiveButton("Log Out", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { toast("Logged out (demo)"); }
                    }).setNegativeButton("Cancel", null).show();
        }});
        c.addView(out);
        sv.addView(c, new ScrollView.LayoutParams(-1, -2)); return sv;
    }

    private void setting(LinearLayout parent, String label, String sub, int icon) {
        LinearLayout r = ll(this, LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Theme.dp(this, 16), Theme.dp(this, 12), Theme.dp(this, 14), Theme.dp(this, 12));
        Views.IconView i = new Views.IconView(this).type(icon).color(Theme.WHITE);
        i.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 22), Theme.dp(this, 22))); r.addView(i);
        LinearLayout t = ll(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1); tp.leftMargin = Theme.dp(this, 14);
        TextView a = new TextView(this); a.setText(label); a.setTextColor(Theme.WHITE); a.setTextSize(Theme.sp(this, 14));
        TextView b = new TextView(this); b.setText(sub); b.setTextColor(Theme.MUTED); b.setTextSize(Theme.sp(this, 11));
        b.setPadding(0, Theme.dp(this, 2), 0, 0); t.addView(a); t.addView(b); r.addView(t, tp);
        Views.IconView arrow = new Views.IconView(this).type(Views.IconView.CHEVRON_RIGHT).color(Theme.GOLD);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 18), Theme.dp(this, 18))); r.addView(arrow);
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAccountSetting(label); }
        });
        parent.addView(r);
    }

    /**
     * Small, functional settings destinations. These are deliberately native
     * dialogs until dedicated settings screens are added; no row is a dead tap.
     */
    private void openAccountSetting(String label) {
        if ("Account".equals(label)) {
            new AlertDialog.Builder(this).setTitle("Account")
                    .setMessage("Manage your Utlwa! profile and account information from Edit Profile.")
                    .setPositiveButton("Done", null).show();
        } else if ("Playback".equals(label)) {
            new AlertDialog.Builder(this).setTitle("Playback")
                    .setItems(new String[]{"Shuffle: " + (shuffle ? "On" : "Off"),
                            "Repeat: " + (repeat ? "On" : "Off")}, (dialog, which) -> {
                        if (which == 0) shuffle = !shuffle;
                        else repeat = !repeat;
                        toast(which == 0 ? "Shuffle " + (shuffle ? "enabled" : "disabled")
                                : "Repeat " + (repeat ? "enabled" : "disabled"));
                    }).show();
        } else if ("Notifications".equals(label)) {
            new AlertDialog.Builder(this).setTitle("Notifications")
                    .setMessage("Playback notifications are used to show the current track and playback controls.")
                    .setPositiveButton("Done", null).show();
        } else if ("Privacy & Security".equals(label)) {
            new AlertDialog.Builder(this).setTitle("Privacy & Security")
                    .setMessage("Review the permissions granted to Utlwa! in your Android system settings.")
                    .setPositiveButton("Done", null).show();
        } else if ("Appearance".equals(label)) {
            new AlertDialog.Builder(this).setTitle("Appearance")
                    .setItems(new String[]{"Dark Mode (current)"}, (dialog, which) -> toast("Utlwa! is using its black-and-gold dark theme."))
                    .show();
        } else {
            new AlertDialog.Builder(this).setTitle("Help & Support")
                    .setMessage("Utlwa! support resources will be available here.")
                    .setPositiveButton("Done", null).show();
        }
    }

    /* ---------------- player ---------------- */
    private View buildMini() {
        LinearLayout m = ll(this, LinearLayout.HORIZONTAL); m.setBackgroundColor(Theme.SURFACE);
        m.setPadding(Theme.dp(this, 10), Theme.dp(this, 6), Theme.dp(this, 14), Theme.dp(this, 6));
        artMini = new Views.ArtworkView(this).seed(1).corner(7);
        int s = Theme.dp(this, 42); artMini.setLayoutParams(new LinearLayout.LayoutParams(s, s)); m.addView(artMini);
        LinearLayout t = ll(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1); tp.leftMargin = Theme.dp(this, 12);
        miniTitle = new TextView(this); miniTitle.setTextColor(Theme.WHITE);
        miniTitle.setTextSize(Theme.sp(this, 13)); miniTitle.setTypeface(null, 1); miniTitle.setSingleLine(true);
        miniArtist = new TextView(this); miniArtist.setTextColor(Theme.TEXT_SEC);
        miniArtist.setTextSize(Theme.sp(this, 11)); miniArtist.setSingleLine(true);
        t.addView(miniTitle); t.addView(miniArtist); m.addView(t, tp);
        miniPlayIcon = new Views.IconView(this).type(Views.IconView.PLAY).color(Theme.GOLD).filled(true);
        miniPlayIcon.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 28), Theme.dp(this, 28)));
        miniPlayIcon.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        m.addView(miniPlayIcon);
        m.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showPlayer(); } });
        return m;
    }

    private View buildPlayer() {
        FrameLayout p = new FrameLayout(this); p.setBackgroundColor(Theme.BG);
        ScrollView sv = new ScrollView(this);
        LinearLayout c = ll(this, LinearLayout.VERTICAL);
        c.setPadding(Theme.dp(this, 22), Theme.dp(this, 18), Theme.dp(this, 22), Theme.dp(this, 24));
        LinearLayout top = ll(this, LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        Views.IconView down = new Views.IconView(this).type(Views.IconView.CHEVRON_DOWN).color(Theme.WHITE);
        down.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 28), Theme.dp(this, 28)));
        down.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closePlayer(); } });
        top.addView(down);
        TextView label = new TextView(this); label.setText("NOW PLAYING"); label.setTextColor(Theme.GOLD);
        label.setTextSize(Theme.sp(this, 10)); label.setLetterSpacing(0.2f);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1)); label.setGravity(Gravity.CENTER); top.addView(label);
        Views.IconView more = new Views.IconView(this).type(Views.IconView.MORE).color(Theme.WHITE);
        more.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 26), Theme.dp(this, 26)));
        more.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (current != null) toast("Options for " + current.title);
        }});
        top.addView(more); c.addView(top);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int artSize = Math.min(dm.widthPixels - Theme.dp(this, 52), Theme.dp(this, 380));
        artBig = new Views.ArtworkView(this).seed(1).corner(18);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(artSize, artSize);
        ap.gravity = Gravity.CENTER_HORIZONTAL; ap.topMargin = Theme.dp(this, 22); c.addView(artBig, ap);

        LinearLayout info = ll(this, LinearLayout.HORIZONTAL); info.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tx = ll(this, LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        songTitle = new TextView(this); songTitle.setTextColor(Theme.WHITE);
        songTitle.setTextSize(Theme.sp(this, 22)); songTitle.setTypeface(null, 1); songTitle.setSingleLine(true);
        songArtist = new TextView(this); songArtist.setTextColor(Theme.TEXT_SEC);
        songArtist.setTextSize(Theme.sp(this, 14)); songArtist.setPadding(0, Theme.dp(this, 3), 0, 0);
        tx.addView(songTitle); tx.addView(songArtist); info.addView(tx, tp);
        heartIcon = new Views.IconView(this).type(Views.IconView.HEART).color(Theme.WHITE);
        heartIcon.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, 28), Theme.dp(this, 28)));
        heartIcon.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (current == null) return;
            Backend.toggleLiked(current);
            boolean liked = Backend.isLiked(current);
            heartIcon.type(liked ? Views.IconView.HEART_FILLED : Views.IconView.HEART)
                    .color(liked ? Theme.GOLD : Theme.WHITE);
            toast(liked ? "Saved to Your Library" : "Removed from Your Library");
        }});
        info.addView(heartIcon);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2); ip.topMargin = Theme.dp(this, 24);
        c.addView(info, ip);

        seek = new Views.SeekBarView(this).listener(new Views.SeekBarView.Listener() {
            @Override public void onSeek(float progress) {
                if (mp != null && current != null)
                    try {
                        int d = durationMs();
                        if (d > 0) mp.seekTo((int) (progress * d));
                        timeNow.setText(fmt((int) (progress * d / 1000)));
                    } catch (Exception ignored) {}
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Theme.dp(this, 26)); sp.topMargin = Theme.dp(this, 24);
        c.addView(seek, sp);
        LinearLayout times = ll(this, LinearLayout.HORIZONTAL);
        timeNow = new TextView(this); timeEnd = new TextView(this);
        timeNow.setTextColor(Theme.TEXT_SEC); timeEnd.setTextColor(Theme.TEXT_SEC);
        timeNow.setTextSize(Theme.sp(this, 11)); timeEnd.setTextSize(Theme.sp(this, 11));
        timeNow.setLayoutParams(new LinearLayout.LayoutParams(-2, -2, 1)); times.addView(timeNow); times.addView(timeEnd);
        c.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = ll(this, LinearLayout.HORIZONTAL); controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, Theme.dp(this, 18), 0, 0);
        shuffleIcon = ctrl(Views.IconView.SHUFFLE, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { shuffle = !shuffle;
                shuffleIcon.color(shuffle ? Theme.GOLD : Theme.WHITE); toast(shuffle ? "Shuffle on" : "Shuffle off"); }});
        Views.IconView prev = ctrl(Views.IconView.PREV, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { previousTrack(); }}); prev.filled(true);
        FrameLayout playWrap = new FrameLayout(this); int ps = Theme.dp(this, 68);
        playWrap.setBackground(Theme.goldGradient(100, this));
        playIcon = new Views.IconView(this).type(Views.IconView.PLAY).color(Theme.BG).filled(true);
        playIcon.setLayoutParams(new FrameLayout.LayoutParams(Theme.dp(this, 30), Theme.dp(this, 30), Gravity.CENTER));
        playWrap.addView(playIcon);
        playWrap.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        LinearLayout.LayoutParams pw = new LinearLayout.LayoutParams(ps, ps);
        pw.leftMargin = Theme.dp(this, 10); pw.rightMargin = Theme.dp(this, 10);
        Views.IconView next = ctrl(Views.IconView.NEXT, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { nextTrack(); }}); next.filled(true);
        repeatIcon = ctrl(Views.IconView.REPEAT, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { repeat = !repeat;
                repeatIcon.color(repeat ? Theme.GOLD : Theme.WHITE); toast(repeat ? "Repeat on" : "Repeat off"); }});
        controls.addView(shuffleIcon, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(prev, new LinearLayout.LayoutParams(Theme.dp(this, 30), Theme.dp(this, 30)));
        controls.addView(playWrap, pw);
        controls.addView(next, new LinearLayout.LayoutParams(Theme.dp(this, 30), Theme.dp(this, 30)));
        controls.addView(repeatIcon, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout extra = ll(this, LinearLayout.HORIZONTAL); extra.setGravity(Gravity.CENTER);
        extra.setPadding(0, Theme.dp(this, 26), 0, 0);
        Views.IconView q = ctrl(Views.IconView.QUEUE, 22, new View.OnClickListener() {
            @Override public void onClick(View v) { toast(queue.size() + " songs in queue"); }});
        Views.IconView au = ctrl(Views.IconView.AUDIO, 22, new View.OnClickListener() {
            @Override public void onClick(View v) { toast("Audio output: phone speaker"); }});
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Theme.dp(this, 26), Theme.dp(this, 26));
        ep.leftMargin = Theme.dp(this, 26);
        extra.addView(q, new LinearLayout.LayoutParams(Theme.dp(this, 26), Theme.dp(this, 26)));
        extra.addView(au, ep); c.addView(extra, new LinearLayout.LayoutParams(-1, -2));
        sv.addView(c, new ScrollView.LayoutParams(-1, -2)); p.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        return p;
    }

    private Views.IconView ctrl(int type, int size, View.OnClickListener l) {
        Views.IconView i = new Views.IconView(this).type(type).color(Theme.WHITE);
        i.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(this, size), Theme.dp(this, size)));
        i.setOnClickListener(l); return i;
    }

    private void showPlayer() {
        player.setVisibility(View.VISIBLE); player.startAnimation(Theme.fadeIn(200));
        if (current != null) updatePlayerUi();
    }
    private void closePlayer() { player.setVisibility(View.GONE); }

    private void togglePlay() {
        if (current == null) {
            if (!charts.isEmpty()) { queue = new ArrayList<>(charts); playTrack(charts.get(0)); }
            return;
        }
        try {
            if (playing) { mp.pause(); playing = false; }
            else { mp.start(); playing = true; }
        } catch (Exception ignored) {}
        updatePlayIcons();
        updateNotification();
    }

    private void updatePlayIcons() {
        int t = playing ? Views.IconView.PAUSE : Views.IconView.PLAY;
        if (playIcon != null) playIcon.type(t);
        if (miniPlayIcon != null) miniPlayIcon.type(t);
    }

    /** Key-based membership test so Track objects with the same URL are found even
     *  without equals()/hashCode() (Track has none). */
    private boolean queueContains(Backend.Track t) {
        for (Backend.Track q : queue) if (q.key().equals(t.key())) return true;
        return false;
    }

    private void nextTrack() {
        if (queue.isEmpty()) return;
        int cur = 0;
        for (int k = 0; k < queue.size(); k++) if (queue.get(k).key().equals(current != null ? current.key() : "")) { cur = k; break; }
        int next;
        if (shuffle) {
            // Pick a random index that is not the current one (unless queue size == 1).
            if (queue.size() == 1) { next = 0; }
            else {
                do { next = (int) (Math.random() * queue.size()); } while (next == cur);
            }
        } else {
            next = (cur + 1) % queue.size();
        }
        playTrack(queue.get(next));
    }

    private void previousTrack() {
        if (current == null || queue.isEmpty()) return;
        if (mp != null && mp.getCurrentPosition() > 3000) {
            try { mp.seekTo(0); } catch (Exception ignored) {} return;
        }
        int i = 0;
        for (int k = 0; k < queue.size(); k++) if (queue.get(k).key().equals(current.key())) { i = k; break; }
        // In shuffle mode, previous goes to the linear predecessor, not another random track.
        playTrack(queue.get((i - 1 + queue.size()) % queue.size()));
    }

    private void playTrack(final Backend.Track t) {
        current = t;
        trackMs = 0;
        playing = false;
        audioUrls = new ArrayList<>();
        audioIdx = 0;
        Backend.addRecent(t);
        updatePlayerUi();
        mini.setVisibility(View.VISIBLE);
        showPlayer();
        updateNotification();
        toast("Loading audio: " + t.title);
        Backend.audioUrls(t, new Backend.Callback<List<String>>() {
            @Override public void result(List<String> urls, Exception err) {
                if (!t.key().equals(current.key())) return;
                if (urls == null || urls.isEmpty()) {
                    toast("Couldn't find a playable audio stream");
                    return;
                }
                audioUrls = urls;
                playAudioAt(0, -1, -1);
            }
        });
    }

    private void playAudioAt(final int idx, int what, int extra) {
        if (current == null) return;
        if (idx >= audioUrls.size()) {
            playing = false; updatePlayIcons(); updateNotification();
            toast("Can't stream this song on this connection");
            return;
        }
        if (idx > 0) toast("Trying another audio source…");
        audioIdx = idx;
        try {
            mp.reset();
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            headers.put("Referer", "https://www.youtube.com/");
            mp.setDataSource(MainActivity.this, android.net.Uri.parse(audioUrls.get(idx)), headers);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    trackMs = m.getDuration();
                    timeEnd.setText(fmt(trackMs / 1000));
                    // If we fell back to this source after an error, resume from where the user was.
                    if (positionBeforeError > 0) {
                        try { m.seekTo(positionBeforeError); } catch (Exception ignored) {}
                        positionBeforeError = 0;
                    }
                    m.start(); playing = true; updatePlayIcons(); updateNotification();
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            playAudioAt(idx + 1, -1, -1);
        }
    }

    private void updatePlayerUi() {
        if (songTitle == null || current == null) return;
        songTitle.setText(current.title); songArtist.setText(current.artist);
        miniTitle.setText(current.title); miniArtist.setText(current.artist);
        artBig.url(current.thumb); artMini.url(current.thumb);
        timeEnd.setText(fmt((int) current.duration)); timeNow.setText("0:00"); seek.progress(0);
        boolean liked = Backend.isLiked(current);
        heartIcon.type(liked ? Views.IconView.HEART_FILLED : Views.IconView.HEART)
                .color(liked ? Theme.GOLD : Theme.WHITE);
        updatePlayIcons();
    }

    private static String fmt(int sec) {
        if (sec < 0) sec = 0;
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static LinearLayout ll(android.content.Context c, int orientation) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(orientation); return l;
    }

    /* ---------------- now-playing notification ---------------- */
    private PendingIntent control(String action) {
        Intent i = new Intent(action).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(this, action.hashCode(), i, flags);
    }

    private void updateNotification() {
        if (current == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Utlwa! playback",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Music playing in Utlwa!");
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            int pf = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) pf |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_sys_headset)
                    .setContentTitle(current.title)
                    .setContentText(current.artist)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_TRANSPORT)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.utlwa_logo));
            b.addAction(android.R.drawable.ic_media_previous, "Previous",
                    control("com.buddyapps.utlwa.PREV"));
            b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    playing ? "Pause" : "Play", control("com.buddyapps.utlwa.PLAYPAUSE"));
            b.addAction(android.R.drawable.ic_media_next, "Next",
                    control("com.buddyapps.utlwa.NEXT"));
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable ignored) {}
    }

    static void remotePlayPause() { if (instance != null) instance.togglePlay(); }
    static void remoteNext() { if (instance != null) instance.nextTrack(); }
    static void remotePrev() { if (instance != null) instance.previousTrack(); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        if (noisyReceiver != null) { try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {} }
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) {} mp.release(); }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
