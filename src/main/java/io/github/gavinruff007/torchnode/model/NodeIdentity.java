package io.github.gavinruff007.torchnode.model;

/** Public-key-derived identity, independent of network location. Empty means legacy identity unavailable. */
public record NodeIdentity(String nodeId) {
    public NodeIdentity { nodeId = NodeIds.normalize(nodeId); }
    public boolean available() { return !nodeId.isEmpty(); }
}
