package org.example.persist;

import org.example.command.CommandRegistry;
import org.example.protocol.Reply;
import org.example.protocol.RespParser;
import org.example.server.ClientContext;
import org.example.store.ByteKey;
import org.example.store.Keyspace;
import org.example.store.RedisDb;
import org.example.store.Value;
import org.example.types.RZSet;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;

public final class AofWriter implements AutoCloseable {
    public enum FsyncPolicy { ALWAYS, EVERYSEC, NO }

    private final Path aofPath;
    private final FileChannel channel;
    private FsyncPolicy fsyncPolicy;
    private int currentDb = -1;
    private long lastFsyncMs = System.currentTimeMillis();

    public AofWriter(Path aofPath, FsyncPolicy fsyncPolicy) throws IOException {
        this.aofPath = aofPath;
        this.fsyncPolicy = fsyncPolicy;
        this.channel = FileChannel.open(aofPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    public synchronized void setFsyncPolicy(FsyncPolicy fsyncPolicy) {
        this.fsyncPolicy = fsyncPolicy;
    }

    public synchronized FsyncPolicy getFsyncPolicy() {
        return fsyncPolicy;
    }

    /**
     * Appends a mutating command to AOF, rewriting relative expiry to absolute PEXPIREAT.
     */
    public synchronized void append(int dbIndex, List<byte[]> args) {
        if (args == null || args.isEmpty()) {
            return;
        }

        try {
            // Write SELECT if database changed
            if (dbIndex != currentDb) {
                writeCommand(List.of(
                        "SELECT".getBytes(StandardCharsets.US_ASCII),
                        String.valueOf(dbIndex).getBytes(StandardCharsets.US_ASCII)
                ));
                currentDb = dbIndex;
            }

            // Check if relative expiry needs rewriting to absolute PEXPIREAT
            String cmdName = new String(args.get(0), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            if (cmdName.equals("EXPIRE") && args.size() >= 3) {
                long seconds = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
                long absMs = System.currentTimeMillis() + (seconds * 1000);
                writeCommand(List.of(
                        "PEXPIREAT".getBytes(StandardCharsets.US_ASCII),
                        args.get(1),
                        String.valueOf(absMs).getBytes(StandardCharsets.US_ASCII)
                ));
            } else if (cmdName.equals("PEXPIRE") && args.size() >= 3) {
                long ms = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
                long absMs = System.currentTimeMillis() + ms;
                writeCommand(List.of(
                        "PEXPIREAT".getBytes(StandardCharsets.US_ASCII),
                        args.get(1),
                        String.valueOf(absMs).getBytes(StandardCharsets.US_ASCII)
                ));
            } else {
                writeCommand(args);
            }

            if (fsyncPolicy == FsyncPolicy.ALWAYS) {
                channel.force(true);
            }
        } catch (Exception e) {
            System.err.println("AOF append failed: " + e.getMessage());
        }
    }

    private void writeCommand(List<byte[]> args) throws IOException {
        Reply reply = new Reply();
        reply.arrayHeader(args.size());
        for (byte[] arg : args) {
            reply.bulk(arg);
        }
        byte[] bytes = reply.toBytes();
        channel.write(ByteBuffer.wrap(bytes));
    }

    public synchronized void cronFsync() {
        if (fsyncPolicy == FsyncPolicy.EVERYSEC) {
            long now = System.currentTimeMillis();
            if (now - lastFsyncMs >= 1000) {
                try {
                    channel.force(true);
                    lastFsyncMs = now;
                } catch (IOException e) {
                    System.err.println("AOF everysec fsync failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Rewrites (compacts) the keyspace state into a temporary file and atomically replaces the AOF.
     */
    public synchronized void rewrite(Keyspace keyspace) throws IOException {
        Path tempPath = aofPath.resolveSibling(aofPath.getFileName() + ".rewrite.tmp");
        try (FileChannel tempChannel = FileChannel.open(tempPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            for (RedisDb db : keyspace.getAllDbs()) {
                if (db.size() == 0) continue;

                // SELECT <db>
                writeCommandToChannel(tempChannel, List.of(
                        "SELECT".getBytes(StandardCharsets.US_ASCII),
                        String.valueOf(db.getId()).getBytes(StandardCharsets.US_ASCII)
                ));

                for (Map.Entry<ByteKey, Value> entry : db.getRawEntries().entrySet()) {
                    ByteKey key = entry.getKey();
                    Value val = entry.getValue();

                    switch (val.getType()) {
                        case STRING -> {
                            writeCommandToChannel(tempChannel, List.of(
                                    "SET".getBytes(StandardCharsets.US_ASCII),
                                    key.getBytes(),
                                    val.asString()
                            ));
                        }
                        case LIST -> {
                            List<byte[]> list = val.asList();
                            if (list != null && !list.isEmpty()) {
                                List<byte[]> cmd = new ArrayList<>(list.size() + 2);
                                cmd.add("RPUSH".getBytes(StandardCharsets.US_ASCII));
                                cmd.add(key.getBytes());
                                cmd.addAll(list);
                                writeCommandToChannel(tempChannel, cmd);
                            }
                        }
                        case HASH -> {
                            Map<ByteKey, byte[]> hash = val.asHash();
                            if (hash != null && !hash.isEmpty()) {
                                List<byte[]> cmd = new ArrayList<>(hash.size() * 2 + 2);
                                cmd.add("HSET".getBytes(StandardCharsets.US_ASCII));
                                cmd.add(key.getBytes());
                                for (Map.Entry<ByteKey, byte[]> h : hash.entrySet()) {
                                    cmd.add(h.getKey().getBytes());
                                    cmd.add(h.getValue());
                                }
                                writeCommandToChannel(tempChannel, cmd);
                            }
                        }
                        case SET -> {
                            Set<ByteKey> set = val.asSet();
                            if (set != null && !set.isEmpty()) {
                                List<byte[]> cmd = new ArrayList<>(set.size() + 2);
                                cmd.add("SADD".getBytes(StandardCharsets.US_ASCII));
                                cmd.add(key.getBytes());
                                for (ByteKey s : set) {
                                    cmd.add(s.getBytes());
                                }
                                writeCommandToChannel(tempChannel, cmd);
                            }
                        }
                        case ZSET -> {
                            RZSet zset = val.asZSet();
                            if (zset != null && !zset.isEmpty()) {
                                List<byte[]> cmd = new ArrayList<>(zset.size() * 2 + 2);
                                cmd.add("ZADD".getBytes(StandardCharsets.US_ASCII));
                                cmd.add(key.getBytes());
                                for (Map.Entry<ByteKey, Double> z : zset.getEntries().entrySet()) {
                                    cmd.add(String.valueOf(z.getValue()).getBytes(StandardCharsets.US_ASCII));
                                    cmd.add(z.getKey().getBytes());
                                }
                                writeCommandToChannel(tempChannel, cmd);
                            }
                        }
                    }

                    Long expire = db.getExpireTimestamp(key);
                    if (expire != null) {
                        writeCommandToChannel(tempChannel, List.of(
                                "PEXPIREAT".getBytes(StandardCharsets.US_ASCII),
                                key.getBytes(),
                                String.valueOf(expire).getBytes(StandardCharsets.US_ASCII)
                        ));
                    }
                }
            }

            tempChannel.force(true);
        }

        // Atomically replace existing AOF
        Files.move(tempPath, aofPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeCommandToChannel(FileChannel ch, List<byte[]> args) throws IOException {
        Reply reply = new Reply();
        reply.arrayHeader(args.size());
        for (byte[] arg : args) {
            reply.bulk(arg);
        }
        ch.write(ByteBuffer.wrap(reply.toBytes()));
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            channel.force(true);
        } finally {
            channel.close();
        }
    }
}
