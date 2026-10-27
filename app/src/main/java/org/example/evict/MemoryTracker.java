package org.example.evict;

import org.example.store.ByteKey;
import org.example.store.Keyspace;
import org.example.store.RedisDb;
import org.example.store.Value;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class MemoryTracker {
    public static final long DEFAULT_MAX_MEMORY = 0; // 0 = unlimited

    private final AtomicLong usedMemory = new AtomicLong(1024 * 1024); // 1MB base overhead
    private volatile long maxMemory = DEFAULT_MAX_MEMORY;
    private volatile EvictionPolicy evictionPolicy = new NoEvictionPolicy();
    private final Random random = new Random();

    public long getUsedMemory() {
        return usedMemory.get();
    }

    public long getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(long maxMemory) {
        this.maxMemory = maxMemory;
    }

    public EvictionPolicy getEvictionPolicy() {
        return evictionPolicy;
    }

    public void setEvictionPolicy(EvictionPolicy policy) {
        this.evictionPolicy = policy;
    }

    public void setPolicyByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "noeviction" -> this.evictionPolicy = new NoEvictionPolicy();
            case "allkeys-lru" -> this.evictionPolicy = new SampledLruPolicy(false);
            case "volatile-lru" -> this.evictionPolicy = new SampledLruPolicy(true);
            case "allkeys-lfu" -> this.evictionPolicy = new SampledLfuPolicy(false);
            case "volatile-lfu" -> this.evictionPolicy = new SampledLfuPolicy(true);
            case "allkeys-random" -> this.evictionPolicy = new RandomEvictionPolicy(false);
            case "volatile-random" -> this.evictionPolicy = new RandomEvictionPolicy(true);
            default -> throw new IllegalArgumentException("Unknown eviction policy: " + name);
        }
    }

    public void trackAdd(ByteKey key, Value value) {
        usedMemory.addAndGet(estimateEntrySize(key, value));
    }

    public void trackRemove(ByteKey key, Value value) {
        usedMemory.addAndGet(-estimateEntrySize(key, value));
        if (usedMemory.get() < 0) {
            usedMemory.set(0);
        }
    }

    public static long estimateEntrySize(ByteKey key, Value value) {
        long size = 64; // Dict node overhead
        if (key != null) {
            size += key.getBytes().length + 24;
        }
        if (value != null) {
            size += value.estimateSize();
        }
        return size;
    }

    /**
     * Ensures memory is under limit, evicting if necessary.
     * @return true if memory is acceptable (or freed), false if OOM and cannot evict.
     */
    public boolean checkAndEvict(Keyspace keyspace) {
        if (maxMemory <= 0) {
            return true;
        }
        while (usedMemory.get() > maxMemory) {
            boolean evicted = evictionPolicy.evict(keyspace, this);
            if (!evicted) {
                return false;
            }
        }
        return true;
    }

    public static final class NoEvictionPolicy implements EvictionPolicy {
        @Override
        public String name() { return "noeviction"; }
        @Override
        public boolean evict(Keyspace keyspace, MemoryTracker tracker) {
            return false;
        }
    }

    public final class SampledLruPolicy implements EvictionPolicy {
        private final boolean volatileOnly;
        public SampledLruPolicy(boolean volatileOnly) { this.volatileOnly = volatileOnly; }

        @Override
        public String name() { return volatileOnly ? "volatile-lru" : "allkeys-lru"; }

        @Override
        public boolean evict(Keyspace keyspace, MemoryTracker tracker) {
            long oldestLru = Long.MAX_VALUE;
            RedisDb bestDb = null;
            ByteKey bestKey = null;

            for (RedisDb db : keyspace.getAllDbs()) {
                Set<ByteKey> samplePool = volatileOnly ? db.getExpiringKeys() : db.keySet();
                if (samplePool.isEmpty()) continue;

                // Sample up to 5 keys
                List<ByteKey> sample = new ArrayList<>(samplePool);
                int sampleSize = Math.min(5, sample.size());
                for (int i = 0; i < sampleSize; i++) {
                    ByteKey candidate = sample.get(random.nextInt(sample.size()));
                    Value v = db.getRaw(candidate);
                    if (v != null && v.getLastAccessMs() < oldestLru) {
                        oldestLru = v.getLastAccessMs();
                        bestDb = db;
                        bestKey = candidate;
                    }
                }
            }

            if (bestDb != null && bestKey != null) {
                bestDb.del(bestKey);
                return true;
            }
            return false;
        }
    }

    public final class SampledLfuPolicy implements EvictionPolicy {
        private final boolean volatileOnly;
        public SampledLfuPolicy(boolean volatileOnly) { this.volatileOnly = volatileOnly; }

        @Override
        public String name() { return volatileOnly ? "volatile-lfu" : "allkeys-lfu"; }

        @Override
        public boolean evict(Keyspace keyspace, MemoryTracker tracker) {
            int lowestFreq = Integer.MAX_VALUE;
            RedisDb bestDb = null;
            ByteKey bestKey = null;

            for (RedisDb db : keyspace.getAllDbs()) {
                Set<ByteKey> samplePool = volatileOnly ? db.getExpiringKeys() : db.keySet();
                if (samplePool.isEmpty()) continue;

                List<ByteKey> sample = new ArrayList<>(samplePool);
                int sampleSize = Math.min(5, sample.size());
                for (int i = 0; i < sampleSize; i++) {
                    ByteKey candidate = sample.get(random.nextInt(sample.size()));
                    Value v = db.getRaw(candidate);
                    if (v != null && v.getLfuCounter() < lowestFreq) {
                        lowestFreq = v.getLfuCounter();
                        bestDb = db;
                        bestKey = candidate;
                    }
                }
            }

            if (bestDb != null && bestKey != null) {
                bestDb.del(bestKey);
                return true;
            }
            return false;
        }
    }

    public final class RandomEvictionPolicy implements EvictionPolicy {
        private final boolean volatileOnly;
        public RandomEvictionPolicy(boolean volatileOnly) { this.volatileOnly = volatileOnly; }

        @Override
        public String name() { return volatileOnly ? "volatile-random" : "allkeys-random"; }

        @Override
        public boolean evict(Keyspace keyspace, MemoryTracker tracker) {
            for (RedisDb db : keyspace.getAllDbs()) {
                Set<ByteKey> samplePool = volatileOnly ? db.getExpiringKeys() : db.keySet();
                if (!samplePool.isEmpty()) {
                    List<ByteKey> sample = new ArrayList<>(samplePool);
                    ByteKey chosen = sample.get(random.nextInt(sample.size()));
                    db.del(chosen);
                    return true;
                }
            }
            return false;
        }
    }
}
