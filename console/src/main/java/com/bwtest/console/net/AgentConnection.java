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
            case "roleReady" ->
                    listener.onRoleReady(n.path("runId").asText(), n.path("listenAddr").asText());
            case "telemetry" -> {
                java.util.List<Double> per = new java.util.ArrayList<>();
                for (JsonNode v : n.path("perStream")) {
                    per.add(v.asDouble());
                }
                listener.onTelemetry(new Telemetry.Sample(
                        n.path("runId").asText(),
                        n.path("role").asText("source"),
                        n.path("tSecs").asDouble(),
                        n.path("mbps").asDouble(), n.path("pps").asDouble(),
                        n.path("rttMs").asDouble(), n.path("retransmits").asLong(),
                        n.path("cpuPercent").asDouble(), per,
                        parseFrameProgress(n.path("frame"))));
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

    private Telemetry.Summary parseSummary(JsonNode s) {
        JsonNode p = s.path("phases");
        Telemetry.Phases phases = new Telemetry.Phases(
                p.path("connectMs").asDouble(), p.path("handshakeMs").asDouble(),
                p.path("firstByteMs").asDouble(), p.path("rampMs").asDouble(),
                p.path("steadyMs").asDouble(), p.path("teardownMs").asDouble(),
                p.path("srcIoMs").asDouble(), p.path("sinkIoMs").asDouble(),
                p.path("netMs").asDouble());
        return new Telemetry.Summary(
                s.path("runId").asText(), s.path("avgMbps").asDouble(),
                s.path("peakMbps").asDouble(), s.path("bytesTotal").asLong(),
                s.path("p50RttMs").asDouble(), s.path("p95RttMs").asDouble(),
                s.path("p99RttMs").asDouble(), s.path("retransmits").asLong(),
                s.path("sackActive").asBoolean(), phases,
                parseFrameStats(s.path("frame")));
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

    public void prepareSink(String runId, Scenario sc) {
        ObjectNode m = M.createObjectNode();
        m.put("type", "prepareSink");
        m.put("runId", runId);
        m.set("scenario", M.valueToTree(sc));
        send(m);
    }

    public void startSource(String runId, Scenario sc, String targetAddr) {
        ObjectNode m = M.createObjectNode();
        m.put("type", "startSource");
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
