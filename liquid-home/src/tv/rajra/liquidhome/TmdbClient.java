package tv.rajra.liquidhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small TMDB v3 HTTPS client. Tokens are supplied at runtime and never embedded in the app. */
final class TmdbClient {
    interface Results { void done(List<Item> items, String error); }
    interface Detail { void done(JSONObject data, String error); }
    interface Image { void done(Bitmap bitmap); }
    static final class Item {
        int id; String type, title, overview, date, poster, backdrop, profile; double rating;
        String year() { return date != null && date.length() >= 4 ? date.substring(0, 4) : ""; }
        String imagePath() { return "person".equals(type) ? profile : poster; }
    }
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final LruCache<String, CacheEntry> JSON_CACHE = new LruCache<>(40);
    private static final LruCache<String, Bitmap> IMAGE_CACHE = new LruCache<String, Bitmap>(8 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return Math.max(1, value.getByteCount() / 1024); }
    };

    static void search(String token, String query, Results callback) {
        try {
            String url = "https://api.themoviedb.org/3/search/multi?query=" + enc(query)
                    + "&include_adult=false&language=en-IN&page=1";
            JSONObject root = new JSONObject(get(url, token)); JSONArray results = root.optJSONArray("results");
            List<Item> items = new ArrayList<>();
            if (results != null) for (int i = 0; i < results.length(); i++) {
                JSONObject o = results.optJSONObject(i); if (o == null) continue;
                String type = o.optString("media_type", "");
                if (!"movie".equals(type) && !"tv".equals(type) && !"person".equals(type)) continue;
                Item item = new Item(); item.id = o.optInt("id"); item.type = type;
                item.title = "person".equals(type) ? o.optString("name") : o.optString("title", o.optString("name"));
                item.overview = o.optString("overview", ""); item.date = o.optString("release_date", o.optString("first_air_date", ""));
                item.poster = o.optString("poster_path", null); item.backdrop = o.optString("backdrop_path", null);
                item.profile = o.optString("profile_path", null); item.rating = o.optDouble("vote_average", 0);
                items.add(item);
            }
            callback.done(items, null);
        } catch (Exception e) { callback.done(new ArrayList<>(), explain(e)); }
    }

    static void movieDetails(String token, Item item, Detail callback) {
        detail(token, "/" + ("tv".equals(item.type) ? "tv" : "movie") + "/" + item.id
                + "?language=en-IN&append_to_response=credits", callback);
    }
    static void personDetails(String token, int id, Detail callback) {
        detail(token, "/person/" + id + "?language=en-IN&append_to_response=combined_credits", callback);
    }
    static void providers(String token, Item item, Detail callback) {
        detail(token, "/" + ("tv".equals(item.type) ? "tv" : "movie") + "/" + item.id + "/watch/providers", callback);
    }
    private static void detail(String token, String path, Detail callback) {
        try { callback.done(new JSONObject(get("https://api.themoviedb.org/3" + path, token)), null); }
        catch (Exception e) { callback.done(null, explain(e)); }
    }
    static Bitmap image(String path) throws Exception {
        if (path == null || path.isEmpty() || "null".equals(path)) return null;
        String key = path.startsWith("http") ? path : "https://image.tmdb.org/t/p/w500" + path;
        Bitmap cached = IMAGE_CACHE.get(key); if (cached != null) return cached;
        HttpURLConnection c = (HttpURLConnection) new URL(key).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(10000); c.setUseCaches(true);
        try (InputStream in = c.getInputStream()) { Bitmap b = BitmapFactory.decodeStream(in); if (b != null) IMAGE_CACHE.put(key, b); return b; }
        finally { c.disconnect(); }
    }
    private static String get(String url, String token) throws Exception {
        CacheEntry cached = JSON_CACHE.get(url);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.body;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(8000); c.setReadTimeout(10000); c.setUseCaches(true);
        c.setRequestProperty("Authorization", "Bearer " + token); c.setRequestProperty("Accept", "application/json");
        int status = c.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        String body;
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
        if (status < 200 || status >= 300) {
            if (status == 401) throw new Exception("TMDB rejected this token. Check your API Read Access Token.");
            if (status == 429) throw new Exception("TMDB is rate-limiting this request. Wait briefly, then retry.");
            throw new Exception("TMDB request failed (HTTP " + status + ").");
        }
        JSON_CACHE.put(url, new CacheEntry(body, System.currentTimeMillis() + CACHE_TTL_MS)); return body;
    }
    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static String explain(Exception e) {
        String m = e.getMessage();
        if (m != null && m.startsWith("TMDB ")) return m;
        if (m != null && m.contains("Unable to resolve host")) return "No network connection. Connect the TV to the internet and retry.";
        if (m != null && (m.contains("timeout") || m.contains("timed out"))) return "TMDB took too long to respond. Please retry.";
        return "Could not reach TMDB. Check the TV's internet connection and try again.";
    }
    private static final class CacheEntry { final String body; final long expiresAt; CacheEntry(String body, long expiresAt) { this.body = body; this.expiresAt = expiresAt; } }
    private TmdbClient() { }
}
