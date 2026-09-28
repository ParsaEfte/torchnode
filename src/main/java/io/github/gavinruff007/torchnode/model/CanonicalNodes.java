package io.github.gavinruff007.torchnode.model;
import java.util.*;
import java.util.function.Predicate;

/** Identity-deduplicated Observatory views; persisted endpoint rows/evidence remain untouched. */
public final class CanonicalNodes {
    private static String key(NodeRecord node) { return node.identity().available() ? node.identity().nodeId() : node.getKey(); }
    public static List<NodeRecord> views(List<NodeRecord> rows) {
        Map<String,NodeRecord> identities = new LinkedHashMap<>();
        Comparator<NodeRecord> preference = Comparator.comparing((NodeRecord n) -> n.getDiscoverySource().equals("discv4"))
                .thenComparing(NodeRecord::getLastSeen, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(NodeRecord::getKey);
        for (NodeRecord node : rows) identities.merge(key(node), node, (a,b) -> preference.compare(a,b) >= 0 ? a : b);
        return List.copyOf(identities.values());
    }
    public static long count(List<NodeRecord> rows, Predicate<NodeRecord> evidence) {
        return rows.stream().filter(evidence).map(CanonicalNodes::key).distinct().count();
    }
    private CanonicalNodes() {}
}
