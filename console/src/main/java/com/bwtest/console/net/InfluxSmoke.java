package com.bwtest.console.net;

import java.util.List;
import java.util.Map;

/**
 * Round-trip check for {@link InfluxClient}: writes sample + run records in the
 * exact line-protocol shape the Orchestrator emits, then reads them back through
 * the same Flux queries the Past Runs picker uses. Run with BW_INFLUX_* set:
 *
 *   java -cp target/classes:$(cat /tmp/cp.txt) com.bwtest.console.net.InfluxSmoke
 */
public class InfluxSmoke {
    public static void main(String[] args) throws Exception {
        InfluxClient in = new InfluxClient();
        if (!in.isEnabled()) {
            System.out.println("ERROR: BW_INFLUX_URL not set");
            System.exit(2);
        }
        System.out.println("influx: " + in.describe());
        String runId = "smoke-" + System.currentTimeMillis();
        long now = System.currentTimeMillis() * 1_000_000L;

        for (int i = 0; i < 5; i++) {
            in.write("bw_sample,runId=" + runId + ",protocol=Quic,arch=Selector,from=A,to=B"
                    + " tSecs=" + (i * 0.2) + ",mbps=" + (1000 + i * 10) + ",pps=500"
                    + ",rttMs=0.5,cpu=42.5,retransmits=0i "
                    + (now + i * 200_000_000L));
        }
        in.write("bw_run,runId=" + runId + ",protocol=Quic,arch=Selector,threads=4,processes=1"
                + ",tls=true,from=A,to=B"
                + " label=\"QUIC (TLS 1.3) · 4s/1p\",avgMbps=1020.0,peakMbps=1040.0"
                + ",bytesTotal=123456i,p50=0.5,p95=0.9,p99=1.2,retransmits=0i"
                + ",connectMs=1.0,handshakeMs=2.0,firstByteMs=0.5,rampMs=10.0"
                + ",steadyMs=3980.0,teardownMs=0.2 " + now);

        System.out.println("written; waiting for flush...");
        Thread.sleep(2500);

        List<Map<String, String>> runs = in.query(in.fluxListRuns(1));
        System.out.println("bw_run rows: " + runs.size());
        for (Map<String, String> r : runs) {
            if (runId.equals(r.get("runId"))) {
                System.out.println("  FOUND run: label=" + r.get("label")
                        + " protocol=" + r.get("protocol") + " threads=" + r.get("threads")
                        + " avgMbps=" + r.get("avgMbps") + " peakMbps=" + r.get("peakMbps"));
            }
        }
        List<Map<String, String>> samples = in.query(in.fluxRunSamples(runId, 1));
        System.out.println("bw_sample rows for run: " + samples.size());
        for (Map<String, String> s : samples) {
            System.out.println("  t=" + s.get("tSecs") + " mbps=" + s.get("mbps")
                    + " cpu=" + s.get("cpu") + " rtt=" + s.get("rttMs"));
        }

        boolean ok = runs.stream().anyMatch(r -> runId.equals(r.get("runId")))
                && samples.size() == 5;
        System.out.println(ok ? "OK: influx write+query round-trip" : "ERROR: round-trip incomplete");
        System.exit(ok ? 0 : 5);
    }
}
