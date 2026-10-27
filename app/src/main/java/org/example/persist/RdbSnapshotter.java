package org.example.persist;

import org.example.store.Keyspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RdbSnapshotter {
    public record SaveRule(int seconds, int changes) {}

    private final Keyspace keyspace;
    private final Path rdbPath;
    private final List<SaveRule> saveRules = new ArrayList<>();
    private final AtomicBoolean bgsaveInProgress = new AtomicBoolean(false);
    private final AtomicLong dirtyChanges = new AtomicLong(0);
    private volatile long lastSaveTimeMs = System.currentTimeMillis();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rdb-bgsave");
        t.setDaemon(true);
        return t;
    });

    public RdbSnapshotter(Keyspace keyspace, Path rdbPath) {
        this.keyspace = keyspace;
        this.rdbPath = rdbPath;
        // Default Redis save rules: 900s 1 change, 300s 10 changes, 60s 10000 changes
        saveRules.add(new SaveRule(900, 1));
        saveRules.add(new SaveRule(300, 10));
        saveRules.add(new SaveRule(60, 10000));
    }

    public void recordChange() {
        dirtyChanges.incrementAndGet();
    }

    public long getLastSaveTimeMs() {
        return lastSaveTimeMs;
    }

    public boolean isBgsaveInProgress() {
        return bgsaveInProgress.get();
    }

    public synchronized void save() throws IOException {
        RdbWriter.save(rdbPath, keyspace);
        lastSaveTimeMs = System.currentTimeMillis();
        dirtyChanges.set(0);
    }

    public boolean bgsave() {
        if (!bgsaveInProgress.compareAndSet(false, true)) {
            return false;
        }

        executor.submit(() -> {
            try {
                RdbWriter.save(rdbPath, keyspace);
                lastSaveTimeMs = System.currentTimeMillis();
                dirtyChanges.set(0);
            } catch (Exception e) {
                System.err.println("Background RDB save failed: " + e.getMessage());
            } finally {
                bgsaveInProgress.set(false);
            }
        });

        return true;
    }

    public void cronCheck() {
        if (bgsaveInProgress.get()) {
            return;
        }

        long elapsedSeconds = (System.currentTimeMillis() - lastSaveTimeMs) / 1000;
        long changes = dirtyChanges.get();

        for (SaveRule rule : saveRules) {
            if (elapsedSeconds >= rule.seconds() && changes >= rule.changes()) {
                bgsave();
                break;
            }
        }
    }
}
