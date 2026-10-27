package org.example.evict;

import org.example.store.Keyspace;

public interface EvictionPolicy {
    String name();

    /**
     * Evicts keys to reduce memory.
     * @return true if at least one key was evicted, false if no keys could be evicted.
     */
    boolean evict(Keyspace keyspace, MemoryTracker tracker);
}
