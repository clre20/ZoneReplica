package clre20.zoneReplica.update;

import clre20.zoneReplica.ZoneReplica;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 向 GitHub Releases 檢查新版本。
 * 結果快取 10 分鐘，沒有定時輪詢，不會超過 GitHub 每小時 60 次的限制。
 */
public final class UpdateChecker {

    public static final String REPO = "clre20/ZoneReplica";
    public static final String RELEASES_URL = "https://github.com/" + REPO + "/releases";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CACHE_MILLIS = 10 * 60 * 1000L;

    public enum Status { UPDATE_AVAILABLE, LATEST, NEWER, NO_RELEASE, FAILED }

    public record Result(Status status, String current, String latest, String url) {
    }

    private final ZoneReplica plugin;
    private final ExecutorService executor;
    private final HttpClient client;
    private volatile Result cached;
    private volatile long cachedAt;
    private CompletableFuture<Result> inFlight;

    public UpdateChecker(ZoneReplica plugin, ExecutorService executor) {
        this.plugin = plugin;
        this.executor = executor;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    public String currentVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** 最近一次的結果（可能為 null 或已過期）。 */
    public Result cached() {
        return cached;
    }

    /** 非同步檢查；10 分鐘內重複呼叫直接回傳快取。回呼在非同步執行緒完成。 */
    public synchronized CompletableFuture<Result> check() {
        Result c = cached;
        if (c != null && c.status() != Status.FAILED && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
            return CompletableFuture.completedFuture(c);
        }
        if (inFlight != null && !inFlight.isDone()) {
            return inFlight;
        }
        String current = currentVersion();
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ZoneReplica/" + current)
                .GET()
                .build();
        inFlight = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handleAsync((response, error) -> {
                    Result r = parse(current, response, error);
                    cached = r;
                    cachedAt = System.currentTimeMillis();
                    return r;
                }, executor);
        return inFlight;
    }

    private static Result parse(String current, HttpResponse<String> response, Throwable error) {
        if (error != null || response == null) {
            return new Result(Status.FAILED, current, null, RELEASES_URL);
        }
        if (response.statusCode() == 404) {
            return new Result(Status.NO_RELEASE, current, null, RELEASES_URL);
        }
        if (response.statusCode() != 200) {
            return new Result(Status.FAILED, current, null, RELEASES_URL);
        }
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement tag = json.get("tag_name");
            if (tag == null || tag.isJsonNull()) {
                return new Result(Status.NO_RELEASE, current, null, RELEASES_URL);
            }
            String latest = stripV(tag.getAsString());
            JsonElement html = json.get("html_url");
            String url = html == null || html.isJsonNull() ? RELEASES_URL : html.getAsString();
            int cmp = compare(stripV(current), latest);
            Status s = cmp < 0 ? Status.UPDATE_AVAILABLE : cmp == 0 ? Status.LATEST : Status.NEWER;
            return new Result(s, current, latest, url);
        } catch (RuntimeException e) {
            return new Result(Status.FAILED, current, null, RELEASES_URL);
        }
    }

    static String stripV(String v) {
        String s = v == null ? "" : v.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    /** 逐段比較數字（1.2.0 > 1.1.9）；非數字部分視為 0。 */
    static int compare(String a, String b) {
        String[] pa = a.split("[^0-9]+");
        String[] pb = b.split("[^0-9]+");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long x = i < pa.length ? parse(pa[i]) : 0;
            long y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static long parse(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s.length() > 18 ? s.substring(0, 18) : s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
