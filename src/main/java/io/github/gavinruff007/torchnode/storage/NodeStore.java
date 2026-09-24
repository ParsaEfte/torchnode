package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;

import java.util.List;
import java.util.Optional;

public interface NodeStore extends AutoCloseable {
    void save(NodeRecord node);
    void saveAll(List<NodeRecord> nodes);
    Optional<NodeRecord> findByKey(String key);
    NodeRecord findByIp(String ip);
    List<NodeRecord> findAll();
    List<NodeRecord> findByCountry(String country);
    List<NodeRecord> findByType(NodeType type);
    void update(NodeRecord node);
    void delete(String key);
    int count();

    @Override
    void close();
}
