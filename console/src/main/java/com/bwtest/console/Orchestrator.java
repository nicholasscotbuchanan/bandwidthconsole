package com.bwtest.console;

import com.bwtest.console.model.AgentModel;
import com.bwtest.console.model.Capabilities;
import com.bwtest.console.model.RunRecord;
import com.bwtest.console.model.Scenario;
import com.bwtest.console.model.Telemetry;
import com.bwtest.console.net.AgentConnection;
import com.bwtest.console.net.ControlListener;
import com.bwtest.console.net.InfluxClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The report nexus. It owns the live agent registry and run history, routes
 * control-channel events onto the JavaFX thread, and sequences the
 * prepare-sink → role-ready → start-source handshake for each run.
 */
public class Orchestrator implements ControlListener {

    /** Distinct, legible colours for run overlays / 3D bars. */
    private static final Color[] PALETTE = {
            Color.web("#5eead4"), Color.web("#a78bfa"), Color.web("#f472b6"),
            Color.web("#facc15"), Color.web("#60a5fa"), Color.web("#fb923c"),
            Color.web("#34d399"), Color.web("#f87171"), Color.web("#c084fc"),
            Color.web("#4ade80"), Color.web("#38bdf8"), Color.web("#e879f9"),
    };

    public final ObservableList<AgentModel> agents = FXCollections.observableArrayList();
    public final ObservableList<RunRecord> runs = FXCollections.observableArrayList();

    private final Map<String, AgentModel> agentById = new ConcurrentHashMap<>();
    private final Map<String, AgentConnection> connById = new ConcurrentHashMap<>();
    private final Map<String, RunContext> byRun = new ConcurrentHashMap<>();
    private final AtomicInteger runCounter = new AtomicInteger(0);

    /** Optional persistence; inert unless BW_INFLUX_URL is set. */
    public final InfluxClient influx = new InfluxClient();

    /** A run previously stored in InfluxDB, offered in the Past Runs picker. */
    public record PastRun(String runId, String label, String protocol, String arch,
                          int threads, int processes, double avgMbps, double peakMbps,
                          String time) {
        @Override public String toString() {
            return time.length() >= 19 ? time.substring(0, 19).replace('T', ' ')
                    + "  ·  " + label : label;
        }
    }

    private record RunContext(RunRecord record, AgentConnection source,
                              AgentConnection sink, Scenario scenario) {}

    // --- API used by the UI ---

    /** Kick off one run between two agents. Returns the record immediately. */
    public RunRecord startRun(AgentModel source, AgentModel sink, Scenario sc) {
        String runId = java.util.UUID.randomUUID().toString();
        int idx = runCounter.incrementAndGet();
        Color color = PALETTE[(idx - 1) % PALETTE.length];
        RunRecord rec = new RunRecord(runId, idx, sc, source.getName(), sink.getName(), color);
        AgentConnection srcConn = connById.get(source.id);
        AgentConnection sinkConn = connById.get(sink.id);
        if (srcConn == null || sinkConn == null) {
            rec.setState(RunRecord.State.ERROR);
            rec.setMessage("agent disconnected");
            Platform.runLater(() -> runs.add(rec));
            return rec;
        }
        byRun.put(runId, new RunContext(rec, srcConn, sinkConn, sc));
        Platform.runLater(() -> runs.add(rec));
        // Sink first; it replies RoleReady and we then start the source.
        sinkConn.prepareSink(runId, sc);
        return rec;
    }

    /**
     * How a multi-agent run is wired.
     *
     * <p>These answer genuinely different questions, which is why both exist.
     * {@link #INCAST} points every source at one sink — "can this one server feed
     * six edit bays at once?", the case where contention actually bites.
     * {@link #PAIRS} gives each source its own sink — "does throughput scale when
     * nothing is shared?", which isolates per-path capacity from contention.
     */
    public enum FanoutShape {
        INCAST("N sources → 1 sink"),
        PAIRS("N independent pairs");

        public final String label;
        FanoutShape(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /**
     * Start the same scenario concurrently across several agents.
     *
     * <p>Each leg is a normal two-agent run with its own id, record and sink
     * listener — an agent can host several sinks at once because each
     * {@code prepareSink} binds a fresh port. Keeping legs as ordinary runs means
     * every existing view renders them without changes; what ties them together
     * is only the shared {@code groupId}.
     *
     * @return one record per leg, in source order
     */
    public List<RunRecord> startFanout(List<AgentModel> sources, List<AgentModel> sinks,
                                       Scenario sc, FanoutShape shape) {
        List<RunRecord> legs = new ArrayList<>();
        if (sources.isEmpty() || sinks.isEmpty()) return legs;

        String groupId = java.util.UUID.randomUUID().toString();
        int n = sources.size();
        for (int i = 0; i < n; i++) {
            AgentModel src = sources.get(i);
            // Incast funnels everything at the first sink; pairs walk the sink
            // list alongside the sources, wrapping if fewer sinks were given.
            AgentModel sink = shape == FanoutShape.INCAST
                    ? sinks.get(0)
                    : sinks.get(i % sinks.size());
            RunRecord rec = startRun(src, sink, sc);
            rec.groupId = groupId;
            rec.groupLeg = i + 1;
            rec.groupSize = n;
            legs.add(rec);
        }
        return legs;
    }

    /** All legs of a fan-out, given any one of them. */
    public List<RunRecord> legsOf(RunRecord rec) {
        if (rec == null || rec.groupId == null) {
            return rec == null ? List.of() : List.of(rec);
        }
        List<RunRecord> out = new ArrayList<>();
        for (RunRecord r : runs) {
            if (rec.groupId.equals(r.groupId)) out.add(r);
        }
        return out;
    }

    /** Run a list of scenarios back-to-back on the same pair, one at a time. */
    public void startBatch(AgentModel source, AgentModel sink, Deque<Scenario> queue,
                           Consumer<RunRecord> onEach) {
        if (queue.isEmpty()) return;
        Scenario next = queue.pollFirst();
        RunRecord rec = startRun(source, sink, next);
        if (onEach != null) onEach.accept(rec);
        rec.stateProperty().addListener((obs, o, st) -> {
            if (st == RunRecord.State.DONE || st == RunRecord.State.ERROR
                    || st == RunRecord.State.ABORTED) {
                // Small gap so sockets fully release before the next bind.
                new Thread(() -> {
                    try { Thread.sleep(600); } catch (InterruptedException ignore) {}
                    Platform.runLater(() -> startBatch(source, sink, queue, onEach));
                }, "batch-next").start();
            }
        });
    }

    public void abort(RunRecord rec) {
        RunContext ctx = byRun.get(rec.id);
        if (ctx != null) {
            ctx.source.abort(rec.id);
            ctx.sink.abort(rec.id);
            Platform.runLater(() -> {
                if (rec.getState() == RunRecord.State.RUNNING
                        || rec.getState() == RunRecord.State.PREPARING) {
                    rec.setState(RunRecord.State.ABORTED);
                }
            });
        }
    }

    // --- ControlListener (network threads) ---

    @Override
    public void onRegister(AgentConnection conn, String agentId, String name, String os,
                           String arch, String dataAddr, Capabilities caps) {
        connById.put(agentId, conn);
        Platform.runLater(() -> {
            AgentModel m = agentById.computeIfAbsent(agentId, id -> {
                AgentModel a = new AgentModel(id);
                agents.add(a);
                return a;
            });
            m.setName(name);
            m.setOs(os);
            m.setArch(arch);
            m.setDataAddr(dataAddr);
            m.caps = caps;
            m.setOnline(true);
            m.setStatus("ready");
        });
    }

    @Override
    public void onRoleReady(String runId, String listenAddr) {
        RunContext ctx = byRun.get(runId);
        if (ctx == null) return;
        Platform.runLater(() -> ctx.record.setState(RunRecord.State.RUNNING));
        ctx.source.startSource(runId, ctx.scenario, listenAddr);
    }

    @Override
    public void onTelemetry(Telemetry.Sample sample) {
        RunContext ctx = byRun.get(sample.runId());
        if (ctx == null) return;
        Platform.runLater(() -> {
            // Late sink samples can trail the source's RunComplete; drop them.
            if (ctx.record.getState() != RunRecord.State.RUNNING
                    && ctx.record.getState() != RunRecord.State.PREPARING) return;
            (sample.fromSink() ? ctx.record.sinkSamples : ctx.record.samples).add(sample);
        });
        persistSample(ctx, sample);
    }

    @Override
    public void onRunComplete(Telemetry.Summary summary) {
        RunContext ctx = byRun.get(summary.runId());
        if (ctx == null) return;
        // The source is done; tear the sink down too so its listener threads and
        // telemetry sampler stop (they otherwise run until the agent exits).
        ctx.sink.abort(summary.runId());
        Platform.runLater(() -> {
            ctx.record.setSummary(summary);
            ctx.record.setState(RunRecord.State.DONE);
        });
        persistRun(ctx, summary);
    }

    // --- InfluxDB persistence ---

    private void persistSample(RunContext ctx, Telemetry.Sample s) {
        if (!influx.isEnabled()) return;
        Scenario sc = ctx.scenario;
        influx.write("bw_sample"
                + ",runId=" + InfluxClient.esc(s.runId())
                + ",role=" + InfluxClient.esc(s.role())
                + ",protocol=" + InfluxClient.esc(sc.protocol)
                + ",arch=" + InfluxClient.esc(sc.architecture)
                + ",source=" + InfluxClient.esc(ctx.record.sourceName)
                + ",sink=" + InfluxClient.esc(ctx.record.sinkName)
                + " tSecs=" + s.tSecs()
                + ",mbps=" + s.mbps()
                + ",pps=" + s.pps()
                + ",rttMs=" + s.rttMs()
                + ",cpu=" + s.cpuPercent()
                + ",retransmits=" + s.retransmits() + "i"
                + " " + System.currentTimeMillis() * 1_000_000L);
    }

    private void persistRun(RunContext ctx, Telemetry.Summary s) {
        if (!influx.isEnabled()) return;
        Scenario sc = ctx.scenario;
        Telemetry.Phases p = s.phases();
        influx.write("bw_run"
                + ",runId=" + InfluxClient.esc(s.runId())
                + ",protocol=" + InfluxClient.esc(sc.protocol)
                + ",arch=" + InfluxClient.esc(sc.architecture)
                + ",threads=" + sc.threads
                + ",processes=" + sc.processes
                + ",tls=" + sc.tls
                + ",source=" + InfluxClient.esc(ctx.record.sourceName)
                + ",sink=" + InfluxClient.esc(ctx.record.sinkName)
                + " label=\"" + sc.shortLabel().replace("\"", "") + "\""
                + ",avgMbps=" + s.avgMbps()
                + ",peakMbps=" + s.peakMbps()
                + ",bytesTotal=" + s.bytesTotal() + "i"
                + ",p50=" + s.p50RttMs()
                + ",p95=" + s.p95RttMs()
                + ",p99=" + s.p99RttMs()
                + ",retransmits=" + s.retransmits() + "i"
                + ",connectMs=" + p.connectMs()
                + ",handshakeMs=" + p.handshakeMs()
                + ",firstByteMs=" + p.firstByteMs()
                + ",rampMs=" + p.rampMs()
                + ",steadyMs=" + p.steadyMs()
                + ",teardownMs=" + p.teardownMs()
                + " " + System.currentTimeMillis() * 1_000_000L);
    }

    /** List runs previously stored in InfluxDB (newest first). */
    public List<PastRun> listPastRuns() {
        List<PastRun> out = new java.util.ArrayList<>();
        for (Map<String, String> r : influx.query(influx.fluxListRuns(30))) {
            String id = r.getOrDefault("runId", "");
            if (id.isBlank()) continue;
            out.add(new PastRun(id,
                    r.getOrDefault("label", id.substring(0, Math.min(8, id.length()))),
                    r.getOrDefault("protocol", "Tcp"),
                    r.getOrDefault("arch", "Selector"),
                    parseInt(r.get("threads"), 1),
                    parseInt(r.get("processes"), 1),
                    parseDouble(r.get("avgMbps")),
                    parseDouble(r.get("peakMbps")),
                    r.getOrDefault("_time", "")));
        }
        return out;
    }

    /**
     * Reload a stored run into the live views. It becomes a normal RunRecord, so
     * it shows up in the comparison table, the 3D field, and the Gantt exactly
     * like a fresh run. Per-stream detail is not persisted, so replayed runs have
     * an empty per-stream chart.
     */
    public RunRecord loadReplay(PastRun pr) {
        List<Map<String, String>> rows = influx.query(influx.fluxRunSamples(pr.runId(), 30));
        int idx = runCounter.incrementAndGet();
        Scenario sc = new Scenario();
        sc.protocol = pr.protocol();
        sc.architecture = pr.arch();
        sc.threads = pr.threads();
        sc.processes = pr.processes();
        RunRecord rec = new RunRecord(pr.runId() + "-replay", idx, sc,
                "replay", "replay", PALETTE[(idx - 1) % PALETTE.length]);

        double peak = 0;
        for (Map<String, String> r : rows) {
            double t = parseDouble(r.get("tSecs"));
            double mbps = parseDouble(r.get("mbps"));
            peak = Math.max(peak, mbps);
            rec.samples.add(new Telemetry.Sample(rec.id, "source", t, mbps,
                    parseDouble(r.get("pps")), parseDouble(r.get("rttMs")),
                    (long) parseDouble(r.get("retransmits")),
                    parseDouble(r.get("cpu")), List.of(), null));
        }
        // Replays carry only the throughput series Influx stores, so there is no
        // frame report or I/O breakdown to reconstruct.
        rec.setSummary(new Telemetry.Summary(rec.id, pr.avgMbps(),
                peak > 0 ? peak : pr.peakMbps(), 0, 0, 0, 0, 0, false,
                new Telemetry.Phases(0, 0, 0, 0, 0, 0, 0, 0, 0), null));
        rec.setState(RunRecord.State.DONE);
        rec.setMessage("replayed from InfluxDB");
        Platform.runLater(() -> runs.add(rec));
        return rec;
    }

    private static int parseInt(String s, int dflt) {
        try { return s == null ? dflt : (int) Double.parseDouble(s); }
        catch (Exception e) { return dflt; }
    }

    private static double parseDouble(String s) {
        try { return s == null || s.isBlank() ? 0 : Double.parseDouble(s); }
        catch (Exception e) { return 0; }
    }

    @Override
    public void onRunError(String runId, String message) {
        RunContext ctx = byRun.get(runId);
        if (ctx == null) return;
        Platform.runLater(() -> {
            ctx.record.setMessage(message);
            ctx.record.setState(RunRecord.State.ERROR);
        });
    }

    @Override
    public void onLog(String agentId, String level, String message) {
        System.out.println("[agent " + agentId + "/" + level + "] " + message);
    }

    @Override
    public void onDisconnect(String agentId) {
        connById.remove(agentId);
        Platform.runLater(() -> {
            AgentModel m = agentById.get(agentId);
            if (m != null) {
                m.setOnline(false);
                m.setStatus("offline");
            }
        });
    }

    public List<AgentModel> onlineAgents() {
        return agents.filtered(a -> a.onlineProperty().get());
    }
}
