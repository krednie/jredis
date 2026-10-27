package org.example.evict;

import org.example.store.ByteKey;
import org.example.store.Keyspace;
import org.example.store.RedisDb;
import org.example.store.Value;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MemoryAndEvictionTest {

    @Test
    void testMemoryTrackingAndAllKeysLruEviction() {
        Keyspace keyspace = new Keyspace(1);
        MemoryTracker tracker = new MemoryTracker();
        keyspace.setMemoryTracker(tracker);

        RedisDb db = keyspace.getDb(0);

        // Add 5 keys
        for (int i = 0; i < 5; i++) {
            ByteKey k = ByteKey.of("key:" + i);
            Value v = new Value(Value.Type.STRING, ("val:" + i).getBytes(StandardCharsets.US_ASCII));
            db.put(k, v);
        }

        assertEquals(5, db.size());
        long currentMem = tracker.getUsedMemory();
        assertTrue(currentMem > 0);

        // Set maxmemory to slightly less than currentMem and policy to allkeys-lru
        tracker.setPolicyByName("allkeys-lru");
        tracker.setMaxMemory(currentMem - 50);

        // checkAndEvict should evict at least one key to get under limit
        boolean ok = tracker.checkAndEvict(keyspace);
        assertTrue(ok);
        assertTrue(db.size() < 5);
        assertTrue(tracker.getUsedMemory() <= tracker.getMaxMemory());
    }

    @Test
    void testNoEvictionReturnsFalseWhenExceeded() {
        Keyspace keyspace = new Keyspace(1);
        MemoryTracker tracker = new MemoryTracker();
        keyspace.setMemoryTracker(tracker);

        RedisDb db = keyspace.getDb(0);
        db.put(ByteKey.of("k1"), new Value(Value.Type.STRING, "v1".getBytes(StandardCharsets.US_ASCII)));

        tracker.setPolicyByName("noeviction");
        tracker.setMaxMemory(100); // very small limit

        // Eviction cannot free keys under noeviction policy
        boolean ok = tracker.checkAndEvict(keyspace);
        assertFalse(ok);
        assertEquals(1, db.size());
    }
}
