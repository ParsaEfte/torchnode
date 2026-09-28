package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.model.DiscoveryObservation;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** A lifecycle-managed source of observations. Inspection never consumes protocol packet types. */
public interface DiscoveryProvider extends AutoCloseable {
    String protocol();
    void start() throws Exception;
    /** Crawl and drain received observations; after close, drain remaining evidence without network activity. */
    void discover(Consumer<DiscoveryObservation> observer) throws InterruptedException;
    default List<DiscoveryObservation> discover() throws InterruptedException {
        List<DiscoveryObservation> batch = new ArrayList<>();
        discover(batch::add);
        return List.copyOf(batch);
    }
    @Override void close();
}
