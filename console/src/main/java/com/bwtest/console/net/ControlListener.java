package com.bwtest.console.net;

import com.bwtest.console.model.Capabilities;
import com.bwtest.console.model.Telemetry;

/** Callbacks the {@link ControlServer} raises as agents talk to the console.
 *  Implemented by the Orchestrator. All calls arrive on network threads. */
public interface ControlListener {
    void onRegister(AgentConnection conn, String agentId, String name, String os,
                    String arch, String dataAddr, Capabilities caps);
    void onRoleReady(String runId, String listenAddr);
    void onTelemetry(Telemetry.Sample sample);
    void onRunComplete(Telemetry.Summary summary);
    void onRunError(String runId, String message);
    void onLog(String agentId, String level, String message);
    void onDisconnect(String agentId);
}
