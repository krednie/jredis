package org.example.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class ExpiryManager {
    public static final int ACTIVE_EXPIRE_CYCLE_LOOKUPS_PER_LOOP = 20;
    public static final long MAX_EXPIRE_CYCLE_DURATION_MS = 25; // 25ms max per tick

    private final Keyspace keyspace;
    private final Random random = new Random();

    public ExpiryManager(Keyspace keyspace) {
        this.keyspace = keyspace;
    }

    /**
     * Executes the active expire cycle across all databases.
     * Samples keys with an expiry, deletes expired ones, and repeats if >25% were expired.
     */
    public void activeExpireCycle() {
        long start = System.currentTimeMillis();

        for (RedisDb db : keyspace.getAllDbs()) {
            while (true) {
                Set<ByteKey> expiringKeys = db.getExpiringKeys();
                int numKeys = expiringKeys.size();
                if (numKeys == 0) {
                    break;
                }

                List<ByteKey> list = new ArrayList<>(expiringKeys);
                int sampleCount = Math.min(ACTIVE_EXPIRE_CYCLE_LOOKUPS_PER_LOOP, numKeys);
                int expiredCount = 0;

                for (int i = 0; i < sampleCount; i++) {
                    ByteKey key = list.get(random.nextInt(numKeys));
                    if (db.isExpired(key)) {
                        expiredCount++;
                    }
                }

                // If less than 25% expired, move to next db
                if (expiredCount <= sampleCount / 4) {
                    break;
                }

                // Check time budget
                if (System.currentTimeMillis() - start > MAX_EXPIRE_CYCLE_DURATION_MS) {
                    return;
                }
            }
        }
    }
}
