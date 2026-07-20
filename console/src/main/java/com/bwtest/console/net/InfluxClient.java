package com.bwtest.console.net;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * InfluxDB v2 persistence for runs, plus the Flux queries that power Past Runs
 * replay. Configured entirely from the environment; when {@code BW_INFLUX_URL}
 * is unset the client is inert and every call is a cheap no-op, so the console
 * runs perfectly well without Influx.
 *
 *   BW_INFLUX_URL     e.g. http://localhost:8086
 *   BW_INFLUX_TOKEN   API token
 *   BW_INFLUX_ORG     org name
 *   BW_INFLUX_BUCKET  bucket (default "bwtest")
 */
public class InfluxClient {

    private final String url;
    private final String token;
    private final String org;
    private final String bucket;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    /** Line-protocol lines buffered and flushed on a timer to avoid per-sample HTTP. */
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService flusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "influx-flush");
                t.setDaemon(true);
                return t;
            });

    public InfluxClient() {
        Map<String, String> env = System.getenv();
        this.url = trimSlash(env.getOrDefault("BW_INFLUX_URL", ""));
        this.token = env.getOrDefault("BW_INFLUX_TOKEN", "");
        this.org = env.getOrDefault("BW_INFLUX_ORG", "bwtest");
        this.bucket = env.getOrDefault("BW_INFLUX_BUCKET", "bwtest");
        this.enabled = !url.isBlank();
        if (enabled) {
            flusher.scheduleAtFixedRate(this::flush, 1, 1, TimeUnit.SECONDS);
        }
    }

    public boolean isEnabled() { return enabled; }
    public String describe() { return enabled ? url + " / " + bucket : "disabled"; }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Queue a line-protocol record. */
    public void write(String line) {
        if (enabled) pending.add(line);
    }

    private void flush() {
        if (pending.isEmpty()) return;
        StringBuilder body = new StringBuilder();
        String l;
        int n = 0;
        while ((l = pending.poll()) != null && n < 5000) {
            body.append(l).append('\n');
            n++;
        }
        if (body.isEmpty()) return;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/v2/write?org=" + enc(org)
                            + "&bucket=" + enc(bucket) + "&precision=ns"))
                    .header("Authorization", "Token " + token)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                System.err.println("[influx] write failed " + res.statusCode() + ": " + res.body());
            }
        } catch (Exception e) {
            System.err.println("[influx] write error: " + e.getMessage());
        }
    }

    /**
     * Run a Flux query and return rows as name→value maps (annotated CSV parsed,
     * annotation lines dropped). Returns empty when disabled or on error.
     */
    public List<Map<String, String>> query(String flux) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (!enabled) return rows;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/v2/query?org=" + enc(org)))
                    .header("Authorization", "Token " + token)
                    .header("Content-Type", "application/vnd.flux")
                    .header("Accept", "application/csv")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(flux))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                System.err.println("[influx] query failed " + res.statusCode() + ": " + res.body());
                return rows;
            }
            String[] header = null;
            for (String line : res.body().split("\r?\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] cells = line.split(",", -1);
                if (header == null || line.startsWith(",result,table")) {
                    header = cells;
                    continue;
                }
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < Math.min(header.length, cells.length); i++) {
                    if (!header[i].isBlank()) row.put(header[i], cells[i]);
                }
                rows.add(row);
            }
        } catch (Exception e) {
            System.err.println("[influx] query error: " + e.getMessage());
        }
        return rows;
    }

    /** Flux to list stored runs, newest first. */
    public String fluxListRuns(int days) {
        return "from(bucket:\"" + bucket + "\")\n"
                + " |> range(start: -" + days + "d)\n"
                + " |> filter(fn: (r) => r._measurement == \"bw_run\")\n"
                + " |> pivot(rowKey:[\"_time\"], columnKey:[\"_field\"], valueColumn:\"_value\")\n"
                + " |> sort(columns:[\"_time\"], desc: true)\n"
                + " |> limit(n: 200)";
    }

    /** Flux to fetch one run's samples in time order. */
    public String fluxRunSamples(String runId, int days) {
        return "from(bucket:\"" + bucket + "\")\n"
                + " |> range(start: -" + days + "d)\n"
                + " |> filter(fn: (r) => r._measurement == \"bw_sample\" and r.runId == \""
                + runId.replace("\"", "") + "\")\n"
                + " |> pivot(rowKey:[\"_time\"], columnKey:[\"_field\"], valueColumn:\"_value\")\n"
                + " |> sort(columns:[\"tSecs\"])";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Escape a line-protocol tag/measurement value. */
    public static String esc(String s) {
        return s == null ? "" : s.replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }
}
