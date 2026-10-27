package org.example.store;

import java.util.*;

public final class IncrementalDict {
    private static final int INITIAL_SIZE = 16;
    private static final double MAX_LOAD_FACTOR = 1.0;

    public static final class Entry {
        public final ByteKey key;
        public Value value;
        public Entry next;

        public Entry(ByteKey key, Value value, Entry next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final class Table {
        Entry[] buckets;
        int size;
        int mask;

        Table(int capacity) {
            this.buckets = new Entry[capacity];
            this.size = 0;
            this.mask = capacity - 1;
        }
    }

    private Table ht0 = new Table(INITIAL_SIZE);
    private Table ht1 = null;
    private int rehashIdx = -1; // -1 means not rehashing

    public boolean isRehashing() {
        return rehashIdx != -1;
    }

    public int getRehashIdx() {
        return rehashIdx;
    }

    public int size() {
        return ht0.size + (ht1 != null ? ht1.size : 0);
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Moves up to n non-empty buckets from ht0 to ht1.
     * @return 1 if more buckets remain, 0 if rehashing finished.
     */
    public int rehash(int n) {
        if (!isRehashing()) {
            return 0;
        }

        int emptyVisits = n * 10;
        while (n > 0 && ht0.size > 0) {
            if (rehashIdx >= ht0.buckets.length) {
                break;
            }

            while (ht0.buckets[rehashIdx] == null) {
                rehashIdx++;
                emptyVisits--;
                if (emptyVisits <= 0 || rehashIdx >= ht0.buckets.length) {
                    return 1;
                }
            }

            Entry head = ht0.buckets[rehashIdx];
            while (head != null) {
                Entry next = head.next;
                int idx = head.key.hashCode() & ht1.mask;
                head.next = ht1.buckets[idx];
                ht1.buckets[idx] = head;
                ht0.size--;
                ht1.size++;
                head = next;
            }
            ht0.buckets[rehashIdx] = null;
            rehashIdx++;
            n--;
        }

        // Check if finished
        if (ht0.size == 0) {
            ht0 = ht1;
            ht1 = null;
            rehashIdx = -1;
            return 0;
        }

        return 1;
    }

    private void rehashStep() {
        if (isRehashing()) {
            rehash(1);
        }
    }

    private void checkResize() {
        if (isRehashing()) {
            return;
        }
        if ((double) ht0.size / ht0.buckets.length >= MAX_LOAD_FACTOR) {
            int newCap = ht0.buckets.length * 2;
            ht1 = new Table(newCap);
            rehashIdx = 0;
        }
    }

    public Value get(ByteKey key) {
        if (isEmpty()) return null;
        rehashStep();

        int hash = key.hashCode();
        int idx = hash & ht0.mask;
        Entry e = ht0.buckets[idx];
        while (e != null) {
            if (e.key.equals(key)) {
                return e.value;
            }
            e = e.next;
        }

        if (isRehashing()) {
            idx = hash & ht1.mask;
            e = ht1.buckets[idx];
            while (e != null) {
                if (e.key.equals(key)) {
                    return e.value;
                }
                e = e.next;
            }
        }

        return null;
    }

    public Value put(ByteKey key, Value value) {
        rehashStep();
        checkResize();

        Table target = isRehashing() ? ht1 : ht0;
        int hash = key.hashCode();

        // Check if existing in ht0
        int idx0 = hash & ht0.mask;
        Entry e = ht0.buckets[idx0];
        while (e != null) {
            if (e.key.equals(key)) {
                Value old = e.value;
                e.value = value;
                return old;
            }
            e = e.next;
        }

        // Check if existing in ht1
        if (isRehashing()) {
            int idx1 = hash & ht1.mask;
            e = ht1.buckets[idx1];
            while (e != null) {
                if (e.key.equals(key)) {
                    Value old = e.value;
                    e.value = value;
                    return old;
                }
                e = e.next;
            }
        }

        // Insert new entry into target table
        int idx = hash & target.mask;
        target.buckets[idx] = new Entry(key, value, target.buckets[idx]);
        target.size++;
        return null;
    }

    public Value remove(ByteKey key) {
        if (isEmpty()) return null;
        rehashStep();

        int hash = key.hashCode();

        // Remove from ht0
        int idx = hash & ht0.mask;
        Entry prev = null;
        Entry e = ht0.buckets[idx];
        while (e != null) {
            if (e.key.equals(key)) {
                if (prev == null) {
                    ht0.buckets[idx] = e.next;
                } else {
                    prev.next = e.next;
                }
                ht0.size--;
                return e.value;
            }
            prev = e;
            e = e.next;
        }

        // Remove from ht1 if rehashing
        if (isRehashing()) {
            idx = hash & ht1.mask;
            prev = null;
            e = ht1.buckets[idx];
            while (e != null) {
                if (e.key.equals(key)) {
                    if (prev == null) {
                        ht1.buckets[idx] = e.next;
                    } else {
                        prev.next = e.next;
                    }
                    ht1.size--;
                    return e.value;
                }
                prev = e;
                e = e.next;
            }
        }

        return null;
    }

    public boolean containsKey(ByteKey key) {
        return get(key) != null;
    }

    public void clear() {
        ht0 = new Table(INITIAL_SIZE);
        ht1 = null;
        rehashIdx = -1;
    }

    public Set<ByteKey> keySet() {
        Set<ByteKey> keys = new HashSet<>();
        for (Entry bucket : ht0.buckets) {
            Entry e = bucket;
            while (e != null) {
                keys.add(e.key);
                e = e.next;
            }
        }
        if (isRehashing()) {
            for (Entry bucket : ht1.buckets) {
                Entry e = bucket;
                while (e != null) {
                    keys.add(e.key);
                    e = e.next;
                }
            }
        }
        return keys;
    }

    public Map<ByteKey, Value> toMap() {
        Map<ByteKey, Value> map = new HashMap<>();
        for (Entry bucket : ht0.buckets) {
            Entry e = bucket;
            while (e != null) {
                map.put(e.key, e.value);
                e = e.next;
            }
        }
        if (isRehashing()) {
            for (Entry bucket : ht1.buckets) {
                Entry e = bucket;
                while (e != null) {
                    map.put(e.key, e.value);
                    e = e.next;
                }
            }
        }
        return map;
    }
}
