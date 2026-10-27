package org.example.store;

import org.example.types.RZSet;

import java.util.*;

public final class Value {
    public enum Type {
        STRING("string"),
        LIST("list"),
        HASH("hash"),
        SET("set"),
        ZSET("zset");

        private final String redisName;
        Type(String redisName) { this.redisName = redisName; }
        public String getRedisName() { return redisName; }
    }

    private final Type type;
    private Object data;
    private long lastAccessMs;
    private int lfuCounter; // 0..255 Morris counter
    private long lastLfuDecayMinutes;

    public Value(Type type, Object data) {
        this.type = type;
        this.data = data;
        long now = System.currentTimeMillis();
        this.lastAccessMs = now;
        this.lastLfuDecayMinutes = now / 60000;
        this.lfuCounter = 5; // Initial LFU counter
    }

    public Type getType() {
        return type;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public long getLastAccessMs() {
        return lastAccessMs;
    }

    public int getLfuCounter() {
        decayLfu();
        return lfuCounter;
    }

    public void touch() {
        this.lastAccessMs = System.currentTimeMillis();
        decayLfu();
        // Morris probabilistic increment
        double r = Math.random();
        double p = 1.0 / (lfuCounter * 2 + 1);
        if (r < p && lfuCounter < 255) {
            lfuCounter++;
        }
    }

    private void decayLfu() {
        long nowMinutes = System.currentTimeMillis() / 60000;
        long diff = nowMinutes - lastLfuDecayMinutes;
        if (diff > 0) {
            lfuCounter = (int) Math.max(0, lfuCounter - diff);
            lastLfuDecayMinutes = nowMinutes;
        }
    }

    @SuppressWarnings("unchecked")
    public byte[] asString() {
        return (byte[]) data;
    }

    @SuppressWarnings("unchecked")
    public List<byte[]> asList() {
        return (List<byte[]>) data;
    }

    @SuppressWarnings("unchecked")
    public Map<ByteKey, byte[]> asHash() {
        return (Map<ByteKey, byte[]>) data;
    }

    @SuppressWarnings("unchecked")
    public Set<ByteKey> asSet() {
        return (Set<ByteKey>) data;
    }

    public RZSet asZSet() {
        return (RZSet) data;
    }

    public long estimateSize() {
        long size = 32; // Object header
        switch (type) {
            case STRING -> {
                byte[] b = asString();
                if (b != null) size += b.length + 24;
            }
            case LIST -> {
                List<byte[]> list = asList();
                if (list != null) {
                    size += 32;
                    for (byte[] b : list) {
                        size += (b == null ? 0 : b.length) + 32;
                    }
                }
            }
            case HASH -> {
                Map<ByteKey, byte[]> hash = asHash();
                if (hash != null) {
                    size += 48;
                    for (Map.Entry<ByteKey, byte[]> e : hash.entrySet()) {
                        size += 48 + e.getKey().getBytes().length + (e.getValue() == null ? 0 : e.getValue().length);
                    }
                }
            }
            case SET -> {
                Set<ByteKey> set = asSet();
                if (set != null) {
                    size += 48;
                    for (ByteKey k : set) {
                        size += 40 + k.getBytes().length;
                    }
                }
            }
            case ZSET -> {
                RZSet zset = asZSet();
                if (zset != null) {
                    size += zset.estimateSize();
                }
            }
        }
        return size;
    }
}
