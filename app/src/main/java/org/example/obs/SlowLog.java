package org.example.obs;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class SlowLog {
    public record Entry(long id, long timestampSeconds, long durationMicros, List<byte[]> args) {}

    private final AtomicLong nextId = new AtomicLong(1);
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long slowerThanMicros = 10000; // 10ms default
    private int maxLen = 128;

    public synchronized void setSlowerThanMicros(long slowerThanMicros) {
        this.slowerThanMicros = slowerThanMicros;
    }

    public synchronized long getSlowerThanMicros() {
        return slowerThanMicros;
    }

    public synchronized void setMaxLen(int maxLen) {
        this.maxLen = maxLen;
        while (entries.size() > maxLen) {
            entries.removeLast();
        }
    }

    public synchronized void recordIfSlow(List<byte[]> args, long durationMicros) {
        if (slowerThanMicros < 0 || durationMicros < slowerThanMicros) {
            return;
        }

        long id = nextId.getAndIncrement();
        long nowSeconds = System.currentTimeMillis() / 1000;
        List<byte[]> copyArgs = new ArrayList<>(args);

        entries.addFirst(new Entry(id, nowSeconds, durationMicros, copyArgs));
        while (entries.size() > maxLen) {
            entries.removeLast();
        }
    }

    public synchronized List<Entry> get(int count) {
        List<Entry> result = new ArrayList<>();
        int i = 0;
        for (Entry e : entries) {
            if (count > 0 && i >= count) {
                break;
            }
            result.add(e);
            i++;
        }
        return result;
    }

    public synchronized int len() {
        return entries.size();
    }

    public synchronized void reset() {
        entries.clear();
    }
}
