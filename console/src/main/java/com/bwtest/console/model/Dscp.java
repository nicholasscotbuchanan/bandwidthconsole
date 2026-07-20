package com.bwtest.console.model;

/**
 * The standardised Differentiated Services Code Points, as registered by IANA.
 *
 * A raw 0-63 number is not a useful thing to ask an operator for: only ~21 of
 * those values mean anything to a real network, and the ones that do are known
 * by name (EF, AF31, CS5) rather than by integer. So the control panel offers
 * the registry, and {@link #value} is what actually goes on the wire — the agent
 * writes IP_TOS = value &lt;&lt; 2.
 *
 * {@code use} is the per-hop behaviour the marking asks for, kept short enough
 * to sit in a combo cell; the RFC that defines it is in {@link #rfc}.
 */
public enum Dscp {
    CS0(0,  "CS0 / DF",  "Default — best effort, no promises",              "RFC 2474"),
    LE(1,   "LE",        "Lower effort — first traffic to be dropped",      "RFC 8622"),
    CS1(8,  "CS1",       "Class 1 — scavenger / bulk background",           "RFC 2474"),
    AF11(10, "AF11",     "Assured Forwarding 1, low drop precedence",       "RFC 2597"),
    AF12(12, "AF12",     "Assured Forwarding 1, medium drop precedence",    "RFC 2597"),
    AF13(14, "AF13",     "Assured Forwarding 1, high drop precedence",      "RFC 2597"),
    CS2(16, "CS2",       "Class 2 — OAM / network management",              "RFC 2474"),
    AF21(18, "AF21",     "Assured Forwarding 2, low drop precedence",       "RFC 2597"),
    AF22(20, "AF22",     "Assured Forwarding 2, medium drop precedence",    "RFC 2597"),
    AF23(22, "AF23",     "Assured Forwarding 2, high drop precedence",      "RFC 2597"),
    CS3(24, "CS3",       "Class 3 — call signalling",                       "RFC 2474"),
    AF31(26, "AF31",     "Assured Forwarding 3, low drop precedence",       "RFC 2597"),
    AF32(28, "AF32",     "Assured Forwarding 3, medium drop precedence",    "RFC 2597"),
    AF33(30, "AF33",     "Assured Forwarding 3, high drop precedence",      "RFC 2597"),
    CS4(32, "CS4",       "Class 4 — real-time interactive / video conf",    "RFC 2474"),
    AF41(34, "AF41",     "Assured Forwarding 4, low drop precedence",       "RFC 2597"),
    AF42(36, "AF42",     "Assured Forwarding 4, medium drop precedence",    "RFC 2597"),
    AF43(38, "AF43",     "Assured Forwarding 4, high drop precedence",      "RFC 2597"),
    CS5(40, "CS5",       "Class 5 — broadcast video",                       "RFC 2474"),
    VA(44,  "VA",        "Voice-Admit — CAC-admitted voice",                "RFC 5865"),
    EF(46,  "EF",        "Expedited Forwarding — low loss/latency/jitter",  "RFC 3246"),
    CS6(48, "CS6",       "Class 6 — routing protocol control",              "RFC 2474"),
    CS7(56, "CS7",       "Class 7 — reserved, network control",             "RFC 2474");

    /** The 6-bit code point placed in the DS field. */
    public final int value;
    public final String name;
    public final String use;
    public final String rfc;

    Dscp(int value, String name, String use, String rfc) {
        this.value = value;
        this.name = name;
        this.use = use;
        this.rfc = rfc;
    }

    /** Nearest registered name for a raw code point, for rendering saved runs. */
    public static Dscp of(int value) {
        for (Dscp d : values()) if (d.value == value) return d;
        return CS0;
    }

    /** e.g. {@code "EF (46) — Expedited Forwarding …"}. */
    @Override
    public String toString() {
        return name + " (" + value + ")  —  " + use;
    }
}
