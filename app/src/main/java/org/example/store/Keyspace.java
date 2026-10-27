package org.example.store;

import org.example.evict.MemoryTracker;

import java.util.Arrays;
import java.util.List;

public final class Keyspace {
    public static final int DEFAULT_DB_COUNT = 16;
    private final RedisDb[] databases;
    private final ExpiryManager expiryManager;
    private MemoryTracker memoryTracker;

    public Keyspace() {
        this(DEFAULT_DB_COUNT);
    }

    public Keyspace(int dbCount) {
        this.databases = new RedisDb[dbCount];
        for (int i = 0; i < dbCount; i++) {
            this.databases[i] = new RedisDb(i);
        }
        this.expiryManager = new ExpiryManager(this);
    }

    public void setMemoryTracker(MemoryTracker memoryTracker) {
        this.memoryTracker = memoryTracker;
        for (RedisDb db : databases) {
            db.setMemoryTracker(memoryTracker);
        }
    }

    public MemoryTracker getMemoryTracker() {
        return memoryTracker;
    }

    public RedisDb getDb(int index) {
        if (index < 0 || index >= databases.length) {
            throw new IndexOutOfBoundsException("ERR DB index is out of range");
        }
        return databases[index];
    }

    public List<RedisDb> getAllDbs() {
        return Arrays.asList(databases);
    }

    public int getDbCount() {
        return databases.length;
    }

    public void flushAll() {
        for (RedisDb db : databases) {
            db.clear();
        }
    }

    public void flushDb(int index) {
        getDb(index).clear();
    }

    public int dbsize(int index) {
        return getDb(index).size();
    }

    public int totalKeys() {
        int total = 0;
        for (RedisDb db : databases) {
            total += db.size();
        }
        return total;
    }

    public ExpiryManager getExpiryManager() {
        return expiryManager;
    }
}
