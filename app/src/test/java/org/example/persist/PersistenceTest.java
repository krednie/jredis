package org.example.persist;

import org.example.command.CommandRegistry;
import org.example.command.server.DbCommands;
import org.example.command.string.StringCommands;
import org.example.evict.MemoryTracker;
import org.example.server.ServerContext;
import org.example.store.ByteKey;
import org.example.store.Keyspace;
import org.example.store.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {

    @TempDir
    Path tempDir;

    private CommandRegistry createTestRegistry(Keyspace ks) {
        CommandRegistry reg = new CommandRegistry();
        MemoryTracker mt = new MemoryTracker();
        ks.setMemoryTracker(mt);
        ServerContext sc = new ServerContext(ks, reg, null, null, mt);
        StringCommands.registerAll(reg, sc);
        DbCommands.registerAll(reg, sc);
        return reg;
    }

    @Test
    void testAofSaveAndRecovery() throws Exception {
        Path aofPath = tempDir.resolve("appendonly.aof");
        try (AofWriter writer = new AofWriter(aofPath, AofWriter.FsyncPolicy.ALWAYS)) {
            writer.append(0, List.of(
                    "SET".getBytes(StandardCharsets.US_ASCII),
                    "foo".getBytes(StandardCharsets.US_ASCII),
                    "bar".getBytes(StandardCharsets.US_ASCII)
            ));
            writer.append(0, List.of(
                    "INCR".getBytes(StandardCharsets.US_ASCII),
                    "counter".getBytes(StandardCharsets.US_ASCII)
            ));
        }

        // Replay with AofLoader
        Keyspace ks = new Keyspace();
        CommandRegistry reg = createTestRegistry(ks);

        int loaded = AofLoader.load(aofPath, reg);
        assertEquals(3, loaded); // SELECT 0 + SET + INCR

        Value valFoo = ks.getDb(0).get(ByteKey.of("foo"));
        assertNotNull(valFoo);
        assertEquals("bar", new String(valFoo.asString(), StandardCharsets.US_ASCII));

        Value valCounter = ks.getDb(0).get(ByteKey.of("counter"));
        assertNotNull(valCounter);
        assertEquals("1", new String(valCounter.asString(), StandardCharsets.US_ASCII));
    }

    @Test
    void testAofTruncatedCrashRecovery() throws Exception {
        Path aofPath = tempDir.resolve("appendonly.aof");
        try (AofWriter writer = new AofWriter(aofPath, AofWriter.FsyncPolicy.ALWAYS)) {
            writer.append(0, List.of(
                    "SET".getBytes(StandardCharsets.US_ASCII),
                    "k1".getBytes(StandardCharsets.US_ASCII),
                    "v1".getBytes(StandardCharsets.US_ASCII)
            ));
        }

        // Append truncated garbage (mid-command crash simulation)
        Files.write(aofPath, "*3\r\n$3\r\nSET\r\n$2\r\nk2\r\n$".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.APPEND);

        Keyspace ks = new Keyspace();
        CommandRegistry reg = createTestRegistry(ks);

        // Loading must survive the corrupt trailing bytes without crashing
        int loaded = AofLoader.load(aofPath, reg);
        assertTrue(loaded >= 2); // SELECT + k1

        Value valK1 = ks.getDb(0).get(ByteKey.of("k1"));
        assertNotNull(valK1);
        assertEquals("v1", new String(valK1.asString(), StandardCharsets.US_ASCII));
    }

    @Test
    void testAofRewrite() throws Exception {
        Path aofPath = tempDir.resolve("appendonly.aof");
        Keyspace ks = new Keyspace();
        ks.getDb(0).put(ByteKey.of("rewritten_key"), new Value(Value.Type.STRING, "hello".getBytes(StandardCharsets.UTF_8)));

        try (AofWriter writer = new AofWriter(aofPath, AofWriter.FsyncPolicy.ALWAYS)) {
            writer.rewrite(ks);
        }

        assertTrue(Files.exists(aofPath));
        assertTrue(Files.size(aofPath) > 0);

        // Verify replaying rewritten AOF
        Keyspace freshKs = new Keyspace();
        CommandRegistry reg = createTestRegistry(freshKs);

        AofLoader.load(aofPath, reg);
        Value val = freshKs.getDb(0).get(ByteKey.of("rewritten_key"));
        assertNotNull(val);
        assertEquals("hello", new String(val.asString(), StandardCharsets.UTF_8));
    }

    @Test
    void testRdbSnapshotSaveAndLoad() throws Exception {
        Path rdbPath = tempDir.resolve("dump.rdb");
        Keyspace ks = new Keyspace();
        ks.getDb(0).put(ByteKey.of("rdb_key"), new Value(Value.Type.STRING, "snap_val".getBytes(StandardCharsets.UTF_8)));

        // Save
        RdbWriter.save(rdbPath, ks);
        assertTrue(Files.exists(rdbPath));

        // Load into fresh keyspace
        Keyspace freshKs = new Keyspace();
        int loaded = RdbLoader.load(rdbPath, freshKs);
        assertEquals(1, loaded);

        Value val = freshKs.getDb(0).get(ByteKey.of("rdb_key"));
        assertNotNull(val);
        assertEquals("snap_val", new String(val.asString(), StandardCharsets.UTF_8));
    }
}
