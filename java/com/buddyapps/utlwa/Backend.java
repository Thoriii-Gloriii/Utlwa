package com.buddyapps.utlwa;

import android.content.Context;
import android.content.SharedPreferences;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real music data via TeamNewPipe/NewPipeExtractor (YouTube). No mock content. */
final class Backend {
    static final class Track {
        final String title, artist, url, thumb;
        final int service;
        final long duration;
        Track(String title, String artist, String url, String thumb, int service, long duration) {
            this.title = title; this.artist = artist; this.url = url;
            this.thumb = thumb; this.service = service; this.duration = duration;
        }
        String key() { return url; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Track track = (Track) o;
            return url != null ? url.equals(track.url) : track.url == null;
        }

        @Override public int hashCode() {
            return url != null ? url.hashCode() : 0;
        }
    }

    private static boolean ready = false;
    private static Context app;

    static synchronized void init(Context c) {
        app = c.getApplicationContext();
        if (ready) return;
        try {
            NewPipe.init(new AndroidDownloader(), new Localization("en", "US"));
            ready = true;
        } catch (Throwable e) {
            lastError = e.getClass().getName() + ": " + e.getMessage();
            ready = false;
        }
    }

    /** The same downloader NewPipe uses on Android, built on HttpURLConnection. */
    static final class AndroidDownloader extends Downloader {
        @Override public Response execute(Request r) throws IOException, ReCaptchaException {
            HttpURLConnection c = (HttpURLConnection) new URL(r.url()).openConnection();
            c.setRequestMethod(r.httpMethod());
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (r.headers() != null)
                for (Map.Entry<String, List<String>> e : r.headers().entrySet())
                    for (String v : e.getValue()) c.setRequestProperty(e.getKey(), v);
            byte[] data = r.dataToSend();
            if (data != null) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(data); os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
            Map<String, List<String>> h = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet())
                if (e.getKey() != null) h.put(e.getKey(), e.getValue());
            return new Response(code, c.getResponseMessage(), h, body, c.getURL().toString());
        }
        private static byte[] readAll(InputStream in) throws IOException {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    interface Callback<T> { void result(T value, Exception error); }

    static void search(final String query, final Callback<List<Track>> cb) {
        run(new Work<List<Track>>() {
            @Override public List<Track> go() throws Exception {
                if (!ready) throw new IllegalStateException("Extractor not ready");
                StreamingService s = NewPipe.getService(0);
                SearchExtractor se = s.getSearchExtractor(query);
                se.fetchPage();
                List<InfoItem> items = se.getInitialPage().getItems();
                List<Track> out = new ArrayList<>();
                for (InfoItem it : items)
                    if (it instanceof StreamInfoItem) out.add(convert((StreamInfoItem) it));
                return out;
            }
        }, cb);
    }

    /** YouTube's real Trending Music kiosk. */
    static void musicKiosk(final Callback<List<Track>> cb) {
        run(new Work<List<Track>>() {
            @Override public List<Track> go() throws Exception {
                if (!ready) throw new IllegalStateException("Extractor not ready");
                StreamingService s = NewPipe.getService(0);
                KioskExtractor ke = s.getKioskList().getExtractorById("trending_music", null);
                ke.fetchPage();
                List<InfoItem> items = ke.getInitialPage().getItems();
                List<Track> out = new ArrayList<>();
                for (InfoItem it : items)
                    if (it instanceof StreamInfoItem) out.add(convert((StreamInfoItem) it));
                return out;
            }
        }, cb);
    }

    /** Related songs — used for real "Made For You" recommendations. */
    static void related(final Track t, final Callback<List<Track>> cb) {
        run(new Work<List<Track>>() {
            @Override public List<Track> go() throws Exception {
                StreamingService s = NewPipe.getService(t.service);
                StreamInfo info = StreamInfo.getInfo(s, t.url);
                List<InfoItem> items = info.getRelatedStreams();
                List<Track> out = new ArrayList<>();
                for (InfoItem it : items)
                    if (it instanceof StreamInfoItem) out.add(convert((StreamInfoItem) it));
                if (out.isEmpty()) throw new IllegalStateException("No related songs");
                return out;
            }
        }, cb);
    }

    /** Resolve several real audio stream URLs, most compatible first, so playback can fall back. */
    static void audioUrls(final Track t, final Callback<List<String>> cb) {
        run(new Work<List<String>>() {
            @Override public List<String> go() throws Exception {
                StreamingService s = NewPipe.getService(t.service);
                StreamInfo info = StreamInfo.getInfo(s, t.url);
                List<AudioStream> streams = info.getAudioStreams();
                if (streams.isEmpty()) throw new IllegalStateException("No audio stream available");
                List<AudioStream> sorted = new ArrayList<>(streams);
                java.util.Collections.sort(sorted, new java.util.Comparator<AudioStream>() {
                    @Override public int compare(AudioStream a, AudioStream b) {
                        int sa = score(a), sb = score(b);
                        if (sa != sb) return sb - sa;
                        return b.getBitrate() - a.getBitrate();
                    }
                    int score(AudioStream x) {
                        if (x.getFormat() == org.schabi.newpipe.extractor.MediaFormat.M4A) {
                            return "mp4a.40.2".equals(x.getCodec()) ? 3 : 2;
                        }
                        return 1;
                    }
                });
                List<String> urls = new ArrayList<>();
                for (AudioStream a : sorted) if (!urls.contains(a.getUrl())) urls.add(a.getUrl());
                return urls;
            }
        }, cb);
    }

    private static Track convert(StreamInfoItem it) {
        String thumb = "";
        if (it.getThumbnails() != null && !it.getThumbnails().isEmpty()) {
            org.schabi.newpipe.extractor.Image best = it.getThumbnails().get(0);
            long bestScore = scoreImage(best);
            for (org.schabi.newpipe.extractor.Image img : it.getThumbnails()) {
                long score = scoreImage(img);
                if (score >= bestScore) {
                    best = img;
                    bestScore = score;
                }
            }
            thumb = best.getUrl();
        }
        return new Track(it.getName(), it.getUploaderName(), it.getUrl(), thumb,
                it.getServiceId(), it.getDuration());
    }

    private static long scoreImage(org.schabi.newpipe.extractor.Image img) {
        if (img == null) return -1;
        long w = img.getWidth(), h = img.getHeight();
        if (w > 0 && h > 0) return w * h;
        if (w > 0) return w;
        if (h > 0) return h;
        return 0;
    }

    static volatile String lastError = "";

    interface Work<T> { T go() throws Exception; }
    private static <T> void run(final Work<T> w, final Callback<T> cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                try { final T v = w.go();
                    h.post(new Runnable() { @Override public void run() { cb.result(v, null); } });
                } catch (final Throwable e) {
                    lastError = e.getClass().getName() + ": " + e.getMessage();
                    h.post(new Runnable() { @Override public void run() { cb.result(null, new Exception(lastError)); } });
                }
            }
        }, "utlwa-net").start();
    }

    /* ---- Real local data: play history, favourites, user playlists ---- */
    private static final String PREFS = "utlwa_real";
    private static SharedPreferences prefs() { return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static List<Track> recent() { return readList("recent"); }
    static void addRecent(Track t) {
        List<Track> l = readList("recent");
        List<Track> clean = new ArrayList<>();
        for (Track x : l) if (!x.key().equals(t.key())) clean.add(x);
        clean.add(0, t);
        while (clean.size() > 30) clean.remove(clean.size() - 1);
        writeList("recent", clean);
    }
    static List<Track> liked() { return readList("liked"); }
    static void toggleLiked(Track t) {
        List<Track> l = readList("liked");
        boolean found = false;
        for (Track x : l) if (x.key().equals(t.key())) { found = true; break; }
        List<Track> clean = new ArrayList<>();
        for (Track x : l) if (!x.key().equals(t.key())) clean.add(x);
        if (!found) clean.add(0, t);
        writeList("liked", clean);
    }
    static boolean isLiked(Track t) {
        for (Track x : liked()) if (x.key().equals(t.key())) return true;
        return false;
    }

    static List<String> playlists() {
        List<String> out = new ArrayList<>();
        String raw = prefs().getString("playlists", "");
        for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    static void addPlaylist(String name) {
        List<String> l = playlists();
        l.add(0, name);
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append('\n');
        prefs().edit().putString("playlists", sb.toString()).apply();
    }

    private static List<Track> readList(String key) {
        List<Track> out = new ArrayList<>();
        String raw = prefs().getString(key, "");
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length >= 5) {
                try {
                    out.add(new Track(dec(f[0]), dec(f[1]), dec(f[2]), dec(f[3]),
                            Integer.parseInt(f[4]), f.length > 5 ? Long.parseLong(f[5]) : 0));
                } catch (Exception ignored) {}
            }
        }
        return out;
    }
    private static void writeList(String key, List<Track> list) {
        StringBuilder sb = new StringBuilder();
        for (Track t : list) {
            sb.append(enc(t.title)).append('\t').append(enc(t.artist)).append('\t')
              .append(enc(t.url)).append('\t').append(enc(t.thumb)).append('\t')
              .append(t.service).append('\t').append(t.duration).append('\n');
        }
        prefs().edit().putString(key, sb.toString()).apply();
    }
    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }
    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private Backend() {}
}
