package io.github.gavinruff007.torchnode.enrichment;

/** Implementations must be offline, thread-safe and promptly closable. */
public interface NetworkEnrichmentProvider extends AutoCloseable {
    String datasetKey();
    NetworkEnrichment lookup(String normalizedAddress) throws Exception;
    @Override default void close() throws Exception {}
}
