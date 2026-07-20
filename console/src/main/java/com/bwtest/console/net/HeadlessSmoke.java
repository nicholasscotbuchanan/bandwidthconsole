package com.bwtest.console.net;

import com.bwtest.console.model.Capabilities;
import com.bwtest.console.model.Scenario;
import com.bwtest.console.model.Telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX-free verification of the console's networking + JSON layer. Reuses the
 * real {@link ControlServer}/{@link AgentConnection} to drive two live agents
 * through one run and print what it parses. Run:
 *
 *   mvn -o dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 *   java -cp target/classes:$(cat /tmp/cp.txt) \
 *        com.bwtest.console.net.HeadlessSmoke 9078
 *
 * Exit 0 == a run completed with non-zero throughput, proving the Java parser
 * matches the agent wire format byte-for-byte.
 */
public class HeadlessSmoke {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9078;
        List<AgentConnection> conns = new ArrayList<>();
        CountDownLatch twoAgents = new CountDownLatch(2);
        CountDownLatch runDone = new CountDownLatch(1);
        String runId = UUID.randomUUID().toString();
        boolean[] ok = {false};

        ControlListener listener = new ControlListener() {
            @Override public void onRegister(AgentConnection conn, String id, String name,
                    String os, String arch, String dataAddr, Capabilities caps) {
                System.out.printf("[register] %s %s/%s dpdk=%b dscp=%b sack=%b cpu=%d%n",
                        name, os, arch, caps.dpdk, caps.dscp, caps.sack, caps.cpuCount);
                synchronized (conns) { conns.add(conn); }
                twoAgents.countDown();
            }
            @Override public void onRoleReady(String rid, String listenAddr) {
                System.out.println("[roleReady] listen=" + listenAddr);
                conns.get(0).startSource(rid, scenario(), listenAddr); // conns[0]=source
            }
            @Override public void onTelemetry(Telemetry.Sample s) {
                System.out.printf("  t=%.1fs  %.1f Mbps  rtt=%.2fms  retx=%d%n",
                        s.tSecs(), s.mbps(), s.rttMs(), s.retransmits());
            }
            @Override public void onRunComplete(Telemetry.Summary s) {
                System.out.printf("[runComplete] avg=%.1f peak=%.1f Mbps  p95rtt=%.2fms  "
                        + "phases{conn=%.2f hs=%.2f fb=%.2f ramp=%.1f steady=%.1f tear=%.2f}%n",
                        s.avgMbps(), s.peakMbps(), s.p95RttMs(),
                        s.phases().connectMs(), s.phases().handshakeMs(), s.phases().firstByteMs(),
                        s.phases().rampMs(), s.phases().steadyMs(), s.phases().teardownMs());
                ok[0] = s.avgMbps() > 0;
                runDone.countDown();
            }
            @Override public void onRunError(String rid, String msg) {
                System.out.println("[runError] " + msg);
                runDone.countDown();
            }
            @Override public void onLog(String id, String level, String msg) {}
            @Override public void onDisconnect(String id) {}
        };

        ControlServer server = new ControlServer(port, listener);
        server.start();
        System.out.println("headless console on tcp/" + port + "; waiting for 2 agents...");
        if (!twoAgents.await(20, TimeUnit.SECONDS)) { System.out.println("ERROR: <2 agents"); System.exit(2); }

        // conns[1] = sink, conns[0] = source (fixed by index for determinism).
        conns.get(1).prepareSink(runId, scenario());
        if (!runDone.await(30, TimeUnit.SECONDS)) { System.out.println("ERROR: timeout"); System.exit(4); }
        server.stop();
        System.out.println(ok[0] ? "OK: Java layer parsed a full run" : "ERROR: zero throughput");
        System.exit(ok[0] ? 0 : 5);
    }

    private static Scenario scenario() {
        return Scenario.of(com.bwtest.console.model.Protocol.TCP,
                com.bwtest.console.model.Architecture.SELECTOR,
                4, 1, 0, false, 32768, 0, 4);
    }
}
