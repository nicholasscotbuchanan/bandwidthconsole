package com.bwtest.console.model;

/** What an agent reported it can do. Mirrors the agent's Capabilities struct. */
public class Capabilities {
    public boolean dpdk;
    public boolean dscp;
    public boolean sack;
    public int maxThreads;
    public int cpuCount;

    public Capabilities() {}

    public Capabilities(boolean dpdk, boolean dscp, boolean sack, int maxThreads, int cpuCount) {
        this.dpdk = dpdk;
        this.dscp = dscp;
        this.sack = sack;
        this.maxThreads = maxThreads;
        this.cpuCount = cpuCount;
    }
}
