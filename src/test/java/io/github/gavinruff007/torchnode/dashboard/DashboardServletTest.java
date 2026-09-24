package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardServletTest {
    @Test
    void inspectedNodeHasHigherDetailScore() {
        NodeRecord basic = new NodeRecord("127.0.0.1", 30303, 30303, "basic");
        NodeRecord detailed = new NodeRecord("127.0.0.2", 30303, 30303, "detailed");
        detailed.setNodeType(NodeType.EXECUTION);
        detailed.setRpcAvailable(true);
        detailed.setLatency(18L);
        detailed.setClientVersion("Geth/v1.14");
        detailed.setBlockNumber(20_000_000L);

        assertTrue(DashboardServlet.detailScore(detailed) > DashboardServlet.detailScore(basic));
    }

    @Test
    void lastSeenSortIgnoresDetailRankingAndPrecedesPagination() {
        NodeRecord oldDetailed = node("192.0.2.1", "2026-01-01T00:00:00Z");
        oldDetailed.setClientVersion("Geth/v1.0");
        oldDetailed.setRpcAvailable(true);
        NodeRecord middle = node("192.0.2.2", "2026-02-01T00:00:00Z");
        NodeRecord newest = node("192.0.2.3", "2026-03-01T00:00:00Z");
        List<NodeRecord> nodes = List.of(oldDetailed, middle, newest);

        assertEquals(List.of(newest, middle, oldDetailed),
                nodes.stream().sorted(DashboardServlet.ordering("seen_desc")).toList());
        assertEquals(List.of(oldDetailed, middle, newest),
                nodes.stream().sorted(DashboardServlet.ordering("seen_asc")).toList());
        assertEquals(oldDetailed,
                nodes.stream().sorted(DashboardServlet.ordering("detail")).findFirst().orElseThrow());
        assertEquals(List.of(newest, middle), nodes.stream()
                .sorted(DashboardServlet.ordering("seen_desc")).limit(2).toList());
    }

    private static NodeRecord node(String ip, String seen) {
        NodeRecord node = new NodeRecord(ip, 30303, 30303, null);
        node.setLastSeen(Instant.parse(seen));
        return node;
    }
}
