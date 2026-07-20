/* C shim between bwagent and DPDK.
 *
 * This file exists for a hard technical reason: DPDK's hot path
 * (rte_eth_rx_burst, rte_eth_tx_burst, rte_pktmbuf_alloc, rte_pktmbuf_append …)
 * is `static inline` in the headers, so those symbols do not exist in any
 * library and cannot be called from Rust via FFI. Every such call must be
 * wrapped in a real, linkable function — that is what lives here.
 *
 * Threading contract (kept deliberately simple, enforced by the Rust side):
 *   - bw_dpdk_init / bw_dpdk_port_init are called once, from one thread.
 *   - bw_dpdk_rx is called from exactly ONE poller thread (it owns a static
 *     burst cache, which is why it must not be called concurrently).
 *   - bw_dpdk_tx may be called from many threads; the tx queue is guarded by a
 *     spinlock since a DPDK tx queue is not thread-safe.
 */
#include <rte_eal.h>
#include <rte_ethdev.h>
#include <rte_mbuf.h>
#include <rte_spinlock.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#define MAX_ARGS 64
#define BURST 32
#define MBUF_CACHE 250
#define NUM_MBUFS 8191

/* Jumbo frames. The bench link is point-to-point, so we run the largest
 * conventional jumbo MTU rather than the 1500 default: fewer, larger frames
 * mean far less per-packet overhead at multi-Gbps rates. Mbufs are sized to
 * hold a whole jumbo frame in one segment, which keeps the datapath
 * single-segment (no chaining, no multi-seg TX offload needed). The peer must
 * agree — the memif vdevs are given a matching bsize in docker-compose.yml. */
#define BW_JUMBO_MTU 9000
#define BW_JUMBO_FRAME (BW_JUMBO_MTU + RTE_ETHER_HDR_LEN + RTE_ETHER_CRC_LEN)
#define BW_MBUF_BUF_SIZE (BW_JUMBO_FRAME + RTE_PKTMBUF_HEADROOM)

static struct rte_mempool *g_pool = NULL;
static rte_spinlock_t g_tx_lock;
static int g_inited = 0;
/* MTU actually negotiated in port_configure — may be below BW_JUMBO_MTU if the
 * PMD cannot do jumbo. The Rust side reads this to bound its sends. */
static uint32_t g_mtu = 0;

/* Split a space-separated EAL argument string into argv and call rte_eal_init. */
int bw_dpdk_init(const char *eal_args) {
    if (g_inited) return 0;
    char *buf = strdup(eal_args ? eal_args : "bwagent");
    if (!buf) return -1;
    char *argv[MAX_ARGS];
    int argc = 0;
    char *save = NULL;
    for (char *t = strtok_r(buf, " ", &save); t && argc < MAX_ARGS; t = strtok_r(NULL, " ", &save)) {
        argv[argc++] = t;
    }
    int ret = rte_eal_init(argc, argv);
    if (ret < 0) {
        free(buf);
        return -2;
    }
    g_pool = rte_pktmbuf_pool_create("BW_MBUF_POOL", NUM_MBUFS, MBUF_CACHE, 0,
                                     BW_MBUF_BUF_SIZE, (int)rte_socket_id());
    if (!g_pool) {
        free(buf);
        return -3;
    }
    rte_spinlock_init(&g_tx_lock);
    g_inited = 1;
    /* buf intentionally leaked: EAL retains pointers into argv. */
    return 0;
}

uint16_t bw_dpdk_port_count(void) { return rte_eth_dev_count_avail(); }

/* MTU negotiated by the last successful port_configure (0 before that). */
uint32_t bw_dpdk_mtu(void) { return g_mtu; }

/* Configure one rx and one tx queue on `port`. Split from start() because for a
 * memif client rte_eth_dev_start() is what dials the server's unix socket, and
 * that can legitimately fail until the peer exists — so the caller retries the
 * start step alone rather than reconfiguring the device each time. */
int bw_dpdk_port_configure(uint16_t port) {
    if (!g_inited) return -1;
    if (!rte_eth_dev_is_valid_port(port)) return -2;

    struct rte_eth_conf conf;
    memset(&conf, 0, sizeof(conf));

    /* Ask for jumbo frames, but never exceed what this PMD can actually do — a
     * too-large MTU makes rte_eth_dev_configure() fail outright (-EINVAL), so
     * clamping degrades to the driver's maximum instead of refusing to start.
     *
     * Both limits have to be honoured. PMDs report them inconsistently: memif
     * advertises a large max_mtu while still enforcing max_rx_pktlen=1518 until
     * its vdev is given a bigger `bsize`, so trusting max_mtu alone lets a 9018
     * frame through to a device that rejects it. Derive an MTU from each and
     * take the smallest. */
    uint32_t mtu = BW_JUMBO_MTU;
    struct rte_eth_dev_info info;
    if (rte_eth_dev_info_get(port, &info) == 0) {
        if (info.max_mtu > 0 && info.max_mtu < mtu) mtu = info.max_mtu;
        uint32_t overhead = RTE_ETHER_HDR_LEN + RTE_ETHER_CRC_LEN;
        if (info.max_rx_pktlen > overhead && info.max_rx_pktlen - overhead < mtu) {
            mtu = info.max_rx_pktlen - overhead;
        }
    }
    conf.rxmode.mtu = mtu;
    g_mtu = mtu;

    int ret = rte_eth_dev_configure(port, 1, 1, &conf);
    if (ret != 0) return ret;

    ret = rte_eth_rx_queue_setup(port, 0, 512, (unsigned)rte_eth_dev_socket_id(port), NULL, g_pool);
    if (ret != 0) return ret;
    ret = rte_eth_tx_queue_setup(port, 0, 512, (unsigned)rte_eth_dev_socket_id(port), NULL);
    if (ret != 0) return ret;
    return 0;
}

/* Start the port (for memif clients: connect to the server socket). Retryable. */
int bw_dpdk_port_start(uint16_t port) {
    if (!g_inited) return -1;
    int ret = rte_eth_dev_start(port);
    if (ret != 0) return ret;
    /* Accept traffic not addressed to our MAC: the link is point-to-point and
     * we do our own filtering in userspace. */
    rte_eth_promiscuous_enable(port);
    return 0;
}

int bw_dpdk_mac(uint16_t port, uint8_t *out6) {
    struct rte_ether_addr a;
    if (rte_eth_macaddr_get(port, &a) != 0) return -1;
    memcpy(out6, a.addr_bytes, 6);
    return 0;
}

/* Transmit one fully-formed Ethernet frame. Returns 1 on success, 0 if the
 * ring was full (caller should retry), negative on error. */
int bw_dpdk_tx(uint16_t port, const uint8_t *frame, uint32_t len) {
    if (!g_inited) return -1;
    struct rte_mbuf *m = rte_pktmbuf_alloc(g_pool);
    if (!m) return 0; /* pool exhausted: treat as backpressure */
    char *d = rte_pktmbuf_append(m, (uint16_t)len);
    if (!d) {
        rte_pktmbuf_free(m);
        return -2;
    }
    memcpy(d, frame, len);
    rte_spinlock_lock(&g_tx_lock);
    uint16_t sent = rte_eth_tx_burst(port, 0, &m, 1);
    rte_spinlock_unlock(&g_tx_lock);
    if (sent == 0) {
        rte_pktmbuf_free(m);
        return 0;
    }
    return 1;
}

/* Receive one frame, copying it into `out`. Uses a static burst cache so we
 * still get the efficiency of rte_eth_rx_burst while handing Rust one frame at
 * a time. MUST be called from a single thread. Returns 1 if a frame was
 * produced, 0 if none available. */
int bw_dpdk_rx(uint16_t port, uint8_t *out, uint32_t cap, uint32_t *out_len) {
    static struct rte_mbuf *cache[BURST];
    static uint16_t have = 0, next = 0;
    static uint16_t cache_port = 0xFFFF;

    if (!g_inited) return -1;
    if (cache_port != port) { /* port changed: drop stale cache */
        for (uint16_t i = next; i < have; i++) rte_pktmbuf_free(cache[i]);
        have = next = 0;
        cache_port = port;
    }
    if (next >= have) {
        have = rte_eth_rx_burst(port, 0, cache, BURST);
        next = 0;
        if (have == 0) return 0;
    }
    struct rte_mbuf *m = cache[next++];
    uint32_t len = rte_pktmbuf_pkt_len(m);
    if (len > cap) len = cap;
    /* Frames from virtual PMDs are single-segment; copy the first segment. */
    memcpy(out, rte_pktmbuf_mtod(m, uint8_t *), len);
    *out_len = len;
    rte_pktmbuf_free(m);
    return 1;
}

/* Link status: 1 up, 0 down. memif reports down until a peer connects. */
int bw_dpdk_link_up(uint16_t port) {
    struct rte_eth_link link;
    memset(&link, 0, sizeof(link));
    if (rte_eth_link_get_nowait(port, &link) != 0) return -1;
    return link.link_status == RTE_ETH_LINK_UP ? 1 : 0;
}
