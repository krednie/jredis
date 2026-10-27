package org.example.persist;

import org.example.store.ByteKey;
import org.example.store.Keyspace;
import org.example.store.RedisDb;
import org.example.store.Value;
import org.example.types.RZSet;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

public final class RdbWriter {
    public static final byte[] MAGIC = "JREDIS01".getBytes(StandardCharsets.US_ASCII);

    public static final int OP_EXPIRE_MS = 0xFC;
    public static final int OP_SELECT_DB = 0xFE;
    public static final int OP_EOF = 0xFF;

    public static final int TYPE_STRING = 0;
    public static final int TYPE_LIST = 1;
    public static final int TYPE_SET = 2;
    public static final int TYPE_ZSET = 3;
    public static final int TYPE_HASH = 4;

    public static void save(Path path, Keyspace keyspace) throws IOException {
        Path tempPath = path.resolveSibling(path.getFileName() + ".rdb.tmp");
        CRC32 crc = new CRC32();

        try (OutputStream fileOut = Files.newOutputStream(tempPath);
             BufferedOutputStream bout = new BufferedOutputStream(fileOut)) {

            // Wrap stream to update CRC
            OutputStream out = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    bout.write(b);
                    crc.update(b);
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    bout.write(b, off, len);
                    crc.update(b, off, len);
                }
            };

            // Magic header
            out.write(MAGIC);

            for (RedisDb db : keyspace.getAllDbs()) {
                if (db.size() == 0) continue;

                // Select DB opcode
                out.write(OP_SELECT_DB);
                writeInt(out, db.getId());

                for (Map.Entry<ByteKey, Value> entry : db.getRawEntries().entrySet()) {
                    ByteKey key = entry.getKey();
                    Value val = entry.getValue();

                    // Optional expiry in ms
                    Long expireMs = db.getExpireTimestamp(key);
                    if (expireMs != null) {
                        out.write(OP_EXPIRE_MS);
                        writeLong(out, expireMs);
                    }

                    // Value type
                    switch (val.getType()) {
                        case STRING -> {
                            out.write(TYPE_STRING);
                            writeByteArray(out, key.getBytes());
                            writeByteArray(out, val.asString());
                        }
                        case LIST -> {
                            out.write(TYPE_LIST);
                            writeByteArray(out, key.getBytes());
                            List<byte[]> list = val.asList();
                            writeInt(out, list == null ? 0 : list.size());
                            if (list != null) {
                                for (byte[] item : list) {
                                    writeByteArray(out, item);
                                }
                            }
                        }
                        case HASH -> {
                            out.write(TYPE_HASH);
                            writeByteArray(out, key.getBytes());
                            Map<ByteKey, byte[]> hash = val.asHash();
                            writeInt(out, hash == null ? 0 : hash.size());
                            if (hash != null) {
                                for (Map.Entry<ByteKey, byte[]> h : hash.entrySet()) {
                                    writeByteArray(out, h.getKey().getBytes());
                                    writeByteArray(out, h.getValue());
                                }
                            }
                        }
                        case SET -> {
                            out.write(TYPE_SET);
                            writeByteArray(out, key.getBytes());
                            Set<ByteKey> set = val.asSet();
                            writeInt(out, set == null ? 0 : set.size());
                            if (set != null) {
                                for (ByteKey s : set) {
                                    writeByteArray(out, s.getBytes());
                                }
                            }
                        }
                        case ZSET -> {
                            out.write(TYPE_ZSET);
                            writeByteArray(out, key.getBytes());
                            RZSet zset = val.asZSet();
                            Map<ByteKey, Double> entries = zset == null ? Map.of() : zset.getEntries();
                            writeInt(out, entries.size());
                            for (Map.Entry<ByteKey, Double> z : entries.entrySet()) {
                                writeByteArray(out, z.getKey().getBytes());
                                writeDouble(out, z.getValue());
                            }
                        }
                    }
                }
            }

            // EOF opcode
            out.write(OP_EOF);

            // 8-byte checksum
            long checksum = crc.getValue();
            for (int i = 7; i >= 0; i--) {
                bout.write((int) ((checksum >>> (i * 8)) & 0xFF));
            }
            bout.flush();
        }

        Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLong(OutputStream out, long v) throws IOException {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((v >>> (i * 8)) & 0xFF));
        }
    }

    private static void writeDouble(OutputStream out, double d) throws IOException {
        writeLong(out, Double.doubleToRawLongBits(d));
    }

    private static void writeByteArray(OutputStream out, byte[] b) throws IOException {
        if (b == null) {
            writeInt(out, -1);
        } else {
            writeInt(out, b.length);
            out.write(b);
        }
    }
}
