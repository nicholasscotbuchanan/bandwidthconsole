package com.bwtest.console.net;

import com.bwtest.console.model.Capabilities;
import com.bwtest.console.model.Scenario;
import com.bwtest.console.model.Telemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** One agent's control connection. Reads NDJSON up, writes NDJSON commands down. */
public class AgentConnection {
    private static final ObjectMapper M = new ObjectMapper();

    private final Socket socket;
    private final ControlListener listener;
    private final OutputStream out;
    private volatile String agentId = "?";

    public AgentConnection(Socket socket, ControlListener listener) throws Exception {
        this.socket = socket;
        this.listener = listener;
        this.socket.setTcpNoDelay(true);
        this.out = socket.getOutputStream();
    }

    public String agentId() { return agentId; }

    /**
     * The address this agent's control connection came from. The agent's own
     * advertised address is a guess about how others reach it; this is observed
     * fact, so it makes a good fallback when that guess does not resolve.
     */
    public String peerHost() {
        java.net.InetAddress a = socket.getInetAddress();
        return a == null ? null : a.getHostAddress();
    }

    /**
     * Append {@code peerHost} to a receiver's candidate list, so a receiver advertising
     * a host its peers cannot resolve (a container name, or a stale
     * {@code --advertise}) still gets connected to. The agent tries candidates
     * left to right and takes the first that answers.
     *
     * <p>{@code listenAddr} is itself a comma-separated list: an agent knows it
     * may be reachable by several names at once and says so. We append rather
     * than replace, and skip a candidate whose host we already have — the
     * common case is a receiver whose advertised address and observed address are
     * the same, where a duplicate would just cost a probe.
     *
     * <p>Returns {@code listenAddr} unchanged when {@code requiresDpdk}: there
     * the receiver listens on its own userspace stack, and the control-plane address
     * would reach the kernel — a fallback would hit the wrong network entirely.
     */
    public static String withFallback(String listenAddr, String peerHost, boolean requiresDpdk) {
        if (requiresDpdk || listenAddr == null || listenAddr.isBlank()) return listenAddr;
        if (peerHost == null || peerHost.isBlank()) return listenAddr;

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        String port = null;
        for (String c : listenAddr.split(",")) {
            c = c.strip();
            if (c.isEmpty()) continue;
            candidates.add(c);
            int colon = c.lastIndexOf(':');
            if (colon >= 0) port = c.substring(colon);
        }
        if (candidates.isEmpty() || port == null) return listenAddr;
        candidates.add(peerHost + port);
        return String.join(",", candidates);
    }

    /** Blocking read loop; run on its own thread. */
    public void run() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                dispatch(M.readTree(line));
            }
        } catch (Exception e) {
            // fallthrough to disconnect
        } finally {
            listener.onDisconnect(agentId);
            try { socket.close(); } catch (Exception ignore) {}
        }
    }

    private void dispatch(JsonNode n) {
        String type = n.path("type").asText();
        switch (type) {
            case "register" -> {
                agentId = n.path("agentId").asText();
                Capabilities c = M.convertValue(n.path("capabilities"), Capabilities.class);
                listener.onRegister(this, agentId, n.path("name").asText(),
                        n.path("os").asText(), n.path("arch").asText(),
                        n.path("dataAddr").asText(), c);
            }
            case "heartbeat" -> { /* liveness only */ }
            case "receiveReady" ->
                    listener.onRoleReady(n.path("runId").asText(), n.path("listenAddr").asText());
            case "telemetry" -> {
                java.util.List<Double> per = new java.util.ArrayList<>();
                for (JsonNode v : n.path("perStream")) {
                    per.add(v.asDouble());
                }
                listener.onTelemetry(new Telemetry.Sample(
                        n.path("runId").asText(),
                        n.path("end").asText("send"),
                        n.path("tSecs").asDouble(),
                        n.path("mbps").asDouble(), n.path("pps").asDouble(),
                        n.path("rttMs").asDouble(), n.path("retransmits").asLong(),
                        n.path("cpuPercent").asDouble(), per,
                        parseFrameProgress(n.path("frame"))));
            }
            case "phase" -> {
                Telemetry.LaneUpdate u = parseLane(n);
                // An agent newer than this console could name a lane we don't
                // know; skipping it beats drawing an unlabelled bar.
                if (u != null) listener.onPhase(u);
            }
            case "runComplete" -> listener.onRunComplete(parseSummary(n.path("summary")));
            case "runError" ->
                    listener.onRunError(n.path("runId").asText(), n.path("message").asText());
            case "log" -> listener.onLog(n.path("agentId").asText(),
                    n.path("level").asText(), n.path("message").asText());
            default -> { /* ignore unknown */ }
        }
    }

    /** Frame progress rides along on telemetry samples; absent on large-file runs. */
    private static Telemetry.FrameProgress parseFrameProgress(JsonNode f) {
        if (f == null || f.isMissingNode() || f.isNull()) return null;
        return new Telemetry.FrameProgress(
                f.path("fps").asDouble(), f.path("framesDone").asLong(),
                f.path("framesDropped").asLong(), f.path("frameMsAvg").asDouble(),
                f.path("openMsAvg").asDouble(), f.path("ioMsAvg").asDouble(),
                f.path("closeMsAvg").asDouble());
    }

    /** One lifecycle-lane update. Null when the lane name isn't one we know. */
    private static Telemetry.LaneUpdate parseLane(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        Telemetry.Lane lane = Telemetry.Lane.fromWire(n.path("lane").asText());
        if (lane == null) return null;
        return new Telemetry.LaneUpdate(
                n.path("runId").asText(), n.path("end").asText("send"), lane,
                n.path("startMs").asDouble(), n.path("endMs").asDouble(),
                n.path("busyMs").asDouble(), n.path("done").asLong(),
                n.path("total").asLong(), n.path("complete").asBoolean());
    }

    private Telemetry.Summary parseSummary(JsonNode s) {
        JsonNode p = s.path("phases");
        Telemetry.Phases phases = new Telemetry.Phases(
                p.path("connectMs").asDouble(), p.path("handshakeMs").asDouble(),
                p.path("firstByteMs").asDouble(), p.path("rampMs").asDouble(),
                p.path("steadyMs").asDouble(), p.path("teardownMs").asDouble(),
                p.path("sendIoMs").asDouble(), p.path("recvIoMs").asDouble(),
                p.path("netMs").asDouble());
        return new Telemetry.Summary(
                s.path("runId").asText(), s.path("avgMbps").asDouble(),
                s.path("peakMbps").asDouble(), s.path("bytesTotal").asLong(),
                s.path("p50RttMs").asDouble(), s.path("p95RttMs").asDouble(),
                s.path("p99RttMs").asDouble(), s.path("retransmits").asLong(),
                s.path("sackActive").asBoolean(), phases,
                parseFrameStats(s.path("frame")), parseLanes(s.path("lanes")));
    }

    /** The sender's final lane states, repeated in the summary so a completed
     *  run renders the same Gantt as one that was watched live. */
    private static java.util.List<Telemetry.LaneUpdate> parseLanes(JsonNode a) {
        java.util.List<Telemetry.LaneUpdate> out = new java.util.ArrayList<>();
        if (a == null || !a.isArray()) return out;
        for (JsonNode n : a) {
            Telemetry.LaneUpdate u = parseLane(n);
            if (u != null) out.add(u);
        }
        return out;
    }

    private static Telemetry.Stage parseStage(JsonNode n) {
        if (n == null || n.isMissingNode()) return Telemetry.Stage.ZERO;
        return new Telemetry.Stage(n.path("minMs").asDouble(),
                n.path("avgMs").asDouble(), n.path("maxMs").asDouble());
    }

    /** The frametest report; absent on large-file runs. */
    private static Telemetry.FrameStats parseFrameStats(JsonNode f) {
        if (f == null || f.isMissingNode() || f.isNull()) return null;
        java.util.List<Long> hist = new java.util.ArrayList<>();
        for (JsonNode v : f.path("histogram")) hist.add(v.asLong());
        java.util.List<Telemetry.Window> windows = new java.util.ArrayList<>();
        for (JsonNode w : f.path("windows")) {
            windows.add(new Telemetry.Window(
                    w.path("label").asText(), w.path("openMs").asDouble(),
                    w.path("ioMs").asDouble(), w.path("frameMs").asDouble(),
                    w.path("mbPerSec").asDouble(), w.path("fps").asDouble()));
        }
        return new Telemetry.FrameStats(
                f.path("framesTransferred").asLong(), f.path("framesDropped").asLong(),
                f.path("bytesTotal").asLong(), f.path("fastestMs").asDouble(),
                f.path("slowestMs").asDouble(),
                parseStage(f.path("file")), parseStage(f.path("create")),
                parseStage(f.path("io")), parseStage(f.path("close")),
                hist, windows);
    }

    // --- outbound commands ---

    public void prepareReceive(String runId, Scenario sc) {
        ObjectNode m = M.createObjectNode();
        m.put("type", "prepareReceive");
        m.put("runId", runId);
        m.set("scenario", M.valueToTree(sc));
        send(m);
    }

    public void startSend(String runId, Scenario sc, String targetAddr) {
        ObjectNode m = M.createObjectNode();
        m.put("type", "startSend");
        m.put("runId", runId);
        m.set("scenario", M.valueToTree(sc));
        m.put("targetAddr", targetAddr);
        send(m);
    }

    public void abort(String runId) {
        ObjectNode m = M.createObjectNode();
        m.put("type", "abort");
        m.put("runId", runId);
        send(m);
    }

    private synchronized void send(ObjectNode m) {
        try {
            out.write((M.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            // connection is going away; the read loop will clean up
        }
    }
}
