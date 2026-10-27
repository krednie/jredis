package org.example.store;

import org.example.evict.MemoryTracker;
import org.example.server.ClientContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RedisDb {
    private final int id;
    private final Map<ByteKey, Value> dict = new ConcurrentHashMap<>();
    private final Map<ByteKey, Long> expires = new ConcurrentHashMap<>();
    private final Map<ByteKey, Set<ClientContext>> watchedKeys = new ConcurrentHashMap<>();
    private MemoryTracker memoryTracker;

    public RedisDb(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public void setMemoryTracker(MemoryTracker memoryTracker) {
        this.memoryTracker = memoryTracker;
    }

    public synchronized boolean isExpired(ByteKey key) {
        Long expireAt = expires.get(key);
        if (expireAt == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expireAt) {
            del(key);
            return true;
        }
        return false;
    }

    public synchronized Value get(ByteKey key) {
        if (isExpired(key)) {
            return null;
        }
        Value val = dict.get(key);
        if (val != null) {
            val.touch();
        }
        return val;
    }

    public synchronized Value getRaw(ByteKey key) {
        if (isExpired(key)) {
            return null;
        }
        return dict.get(key);
    }

    public synchronized void put(ByteKey key, Value value) {
        Value old = dict.put(key, value);
        expires.remove(key);
        if (memoryTracker != null) {
            if (old != null) {
                memoryTracker.trackRemove(key, old);
            }
            memoryTracker.trackAdd(key, value);
        }
        touchKey(key);
    }

    public synchronized void putWithExpire(ByteKey key, Value value, long expireAtMs) {
        Value old = dict.put(key, value);
        expires.put(key, expireAtMs);
        if (memoryTracker != null) {
            if (old != null) {
                memoryTracker.trackRemove(key, old);
            }
            memoryTracker.trackAdd(key, value);
        }
        touchKey(key);
    }

    public synchronized boolean setExpire(ByteKey key, long expireAtMs) {
        if (!dict.containsKey(key) || isExpired(key)) {
            return false;
        }
        expires.put(key, expireAtMs);
        touchKey(key);
        return true;
    }

    public synchronized boolean persist(ByteKey key) {
        if (!dict.containsKey(key) || isExpired(key)) {
            return false;
        }
        Long removed = expires.remove(key);
        if (removed != null) {
            touchKey(key);
            return true;
        }
        return false;
    }

    public synchronized long getTtl(ByteKey key) {
        if (!dict.containsKey(key) || isExpired(key)) {
            return -2;
        }
        Long expireAt = expires.get(key);
        if (expireAt == null) {
            return -1;
        }
        long diff = expireAt - System.currentTimeMillis();
        return diff < 0 ? -2 : (diff + 999) / 1000;
    }

    public synchronized long getPttl(ByteKey key) {
        if (!dict.containsKey(key) || isExpired(key)) {
            return -2;
        }
        Long expireAt = expires.get(key);
        if (expireAt == null) {
            return -1;
        }
        long diff = expireAt - System.currentTimeMillis();
        return diff < 0 ? -2 : diff;
    }

    public synchronized Long getExpireTimestamp(ByteKey key) {
        return expires.get(key);
    }

    public synchronized boolean del(ByteKey key) {
        expires.remove(key);
        Value removed = dict.remove(key);
        if (removed != null) {
            if (memoryTracker != null) {
                memoryTracker.trackRemove(key, removed);
            }
            touchKey(key);
            return true;
        }
        return false;
    }

    public synchronized boolean exists(ByteKey key) {
        return !isExpired(key) && dict.containsKey(key);
    }

    public synchronized int size() {
        // Exclude expired keys
        int count = 0;
        for (ByteKey k : dict.keySet()) {
            if (!isExpired(k)) {
                count++;
            }
        }
        return count;
    }

    public synchronized Set<ByteKey> keySet() {
        Set<ByteKey> set = new HashSet<>();
        for (ByteKey k : dict.keySet()) {
            if (!isExpired(k)) {
                set.add(k);
            }
        }
        return set;
    }

    public synchronized Map<ByteKey, Value> getRawEntries() {
        Map<ByteKey, Value> map = new HashMap<>();
        for (Map.Entry<ByteKey, Value> e : dict.entrySet()) {
            if (!isExpired(e.getKey())) {
                map.put(e.getKey(), e.getValue());
            }
        }
        return map;
    }

    public synchronized Set<ByteKey> getExpiringKeys() {
        return new HashSet<>(expires.keySet());
    }

    public synchronized void clear() {
        dict.clear();
        expires.clear();
        for (Set<ClientContext> clients : watchedKeys.values()) {
            for (ClientContext ctx : clients) {
                ctx.transactionAborted = true;
            }
        }
        watchedKeys.clear();
    }

    public synchronized void watch(ClientContext ctx, ByteKey key) {
        watchedKeys.computeIfAbsent(key, k -> Collections.newSetFromMap(new WeakHashMap<>())).add(ctx);
        ctx.watchKey(id, key);
    }

    public synchronized void touchKey(ByteKey key) {
        Set<ClientContext> clients = watchedKeys.get(key);
        if (clients != null) {
            for (ClientContext ctx : clients) {
                ctx.transactionAborted = true;
            }
        }
    }
}
