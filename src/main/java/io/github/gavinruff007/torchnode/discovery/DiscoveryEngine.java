package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.storage.NodeStore;

/** Protocol-neutral bridge for callers outside the daemon. */
public final class DiscoveryEngine {
    private final DiscoveryProvider provider;
    private final NodeStore store;
    public DiscoveryEngine(DiscoveryProvider provider, NodeStore store) {
        this.provider = provider;
        this.store = store;
    }
    public void syncToDatabase() throws InterruptedException {
        provider.discover(store::saveObservation);
    }
}
