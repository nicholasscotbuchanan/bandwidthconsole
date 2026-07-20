/* Feasibility probe: can DPDK's EAL start, make an mbuf pool, and drive a
 * virtual PMD's rx/tx burst in this container? Everything prints to stderr so
 * nothing is lost to buffering if a call faults. */
#include <rte_eal.h>
#include <rte_ethdev.h>
#include <rte_mbuf.h>
#include <rte_version.h>
#include <stdio.h>
#include <string.h>

#define STEP(...) do { fprintf(stderr, "[probe] " __VA_ARGS__); fputc('\n', stderr); fflush(stderr); } while (0)

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    STEP("calling rte_eal_init");
    int ret = rte_eal_init(argc, argv);
    if (ret < 0) {
        STEP("FAIL: rte_eal_init -> %d", ret);
        return 1;
    }
    STEP("OK: EAL initialised (%s)", rte_version());

    uint16_t nb = rte_eth_dev_count_avail();
    STEP("OK: ports available = %u", nb);

    STEP("creating mbuf pool");
    struct rte_mempool *mp = rte_pktmbuf_pool_create(
        "PROBE_POOL", 1023, 32, 0, RTE_MBUF_DEFAULT_BUF_SIZE, (int)rte_socket_id());
    if (!mp) {
        STEP("FAIL: mbuf pool (rte_errno=%d)", rte_errno);
        return 1;
    }
    STEP("OK: mbuf pool created");

    if (nb == 0) {
        STEP("NOTE: no ports available; pass --vdev");
        return 0;
    }

    struct rte_eth_conf conf;
    memset(&conf, 0, sizeof(conf));
    STEP("configuring port 0");
    ret = rte_eth_dev_configure(0, 1, 1, &conf);
    STEP("dev_configure -> %d", ret);
    if (ret) return 1;

    ret = rte_eth_rx_queue_setup(0, 0, 256, (unsigned)rte_eth_dev_socket_id(0), NULL, mp);
    STEP("rx_queue_setup -> %d", ret);
    if (ret) return 1;
    ret = rte_eth_tx_queue_setup(0, 0, 256, (unsigned)rte_eth_dev_socket_id(0), NULL);
    STEP("tx_queue_setup -> %d", ret);
    if (ret) return 1;

    ret = rte_eth_dev_start(0);
    STEP("dev_start -> %d", ret);
    if (ret) return 1;

    struct rte_ether_addr mac;
    if (rte_eth_macaddr_get(0, &mac) == 0) {
        STEP("port MAC %02x:%02x:%02x:%02x:%02x:%02x",
             mac.addr_bytes[0], mac.addr_bytes[1], mac.addr_bytes[2],
             mac.addr_bytes[3], mac.addr_bytes[4], mac.addr_bytes[5]);
    }

    STEP("tx burst");
    struct rte_mbuf *m = rte_pktmbuf_alloc(mp);
    if (!m) { STEP("FAIL: mbuf alloc"); return 1; }
    char *d = rte_pktmbuf_append(m, 64);
    if (d) memset(d, 0xAB, 64);
    uint16_t sent = rte_eth_tx_burst(0, 0, &m, 1);
    STEP("OK: tx_burst sent %u", sent);
    if (sent == 0) rte_pktmbuf_free(m);

    struct rte_mbuf *bufs[32];
    uint16_t n = rte_eth_rx_burst(0, 0, bufs, 32);
    STEP("OK: rx_burst got %u", n);
    for (uint16_t i = 0; i < n; i++) rte_pktmbuf_free(bufs[i]);

    STEP("PROBE COMPLETE");
    return 0;
}
