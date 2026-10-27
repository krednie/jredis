package org.example.persist;

import org.example.store.ByteKey;
import org.example.store.Keyspace;
import org.example.store.RedisDb;
import org.example.store.Value;
import org.example.types.RZSet;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;

public final class RdbLoader {

    public static int load(Path path, Keyspace keyspace) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return 0;
        }

        int loadedKeys = 0;
        CRC32 crc = new CRC32();

        try (InputStream fileIn = Files.newInputStream(path);
             BufferedInputStream bin = new BufferedInputStream(fileIn)) {

            InputStream in = new InputStream() {
                @Override
                public int read() throws IOException {
                    int b = bin.read();
                    if (b != -1) {
                        crc.update(b);
                    }
                    return b;
                }
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int read = bin.read(b, off, len);
                    if (read > 0) {
                        crc.update(b, off, read);
                    }
                    return read;
                }
            };

            // Magic check
            byte[] magic = in.readNBytes(RdbWriter.MAGIC.length);
            if (!Arrays.equals(magic, RdbWriter.MAGIC)) {
                throw new IOException("Invalid RDB magic header");
            }

            int currentDbId = 0;
            Long nextExpireMs = null;

            while (true) {
                int opcode = in.read();
                if (opcode == -1 || opcode == RdbWriter.OP_EOF) {
                    break;
                }

                if (opcode == RdbWriter.OP_SELECT_DB) {
                    currentDbId = readInt(in);
                    continue;
                }

                if (opcode == RdbWriter.OP_EXPIRE_MS) {
                    nextExpireMs = readLong(in);
                    continue;
                }

                // Value type opcode
                int valueType = opcode;
                byte[] keyBytes = readByteArray(in);
                if (keyBytes == null) {
                    break;
                }
                ByteKey key = ByteKey.of(keyBytes);
                RedisDb db = keyspace.getDb(currentDbId);

                Value value = null;
                switch (valueType) {
                    case RdbWriter.TYPE_STRING -> {
                        byte[] strBytes = readByteArray(in);
                        value = new Value(Value.Type.STRING, strBytes);
                    }
                    case RdbWriter.TYPE_LIST -> {
                        int count = readInt(in);
                        List<byte[]> list = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            list.add(readByteArray(in));
                        }
                        value = new Value(Value.Type.LIST, list);
                    }
                    case RdbWriter.TYPE_HASH -> {
                        int count = readInt(in);
                        Map<ByteKey, byte[]> hash = new HashMap<>(count);
                        for (int i = 0; i < count; i++) {
                            ByteKey field = ByteKey.of(readByteArray(in));
                            byte[] val = readByteArray(in);
                            hash.put(field, val);
                        }
                        value = new Value(Value.Type.HASH, hash);
                    }
                    case RdbWriter.TYPE_SET -> {
                        int count = readInt(in);
                        Set<ByteKey> set = new HashSet<>(count);
                        for (int i = 0; i < count; i++) {
                            set.add(ByteKey.of(readByteArray(in)));
                        }
                        value = new Value(Value.Type.SET, set);
                    }
                    case RdbWriter.TYPE_ZSET -> {
                        int count = readInt(in);
                        RZSet zset = new RZSet();
                        for (int i = 0; i < count; i++) {
                            ByteKey mem = ByteKey.of(readByteArray(in));
                            double score = readDouble(in);
                            zset.add(score, mem);
                        }
                        value = new Value(Value.Type.ZSET, zset);
                    }
                    default -> throw new IOException("Unknown RDB value type: " + valueType);
                }

                // Check expiry before loading
                long now = System.currentTimeMillis();
                if (nextExpireMs == null || nextExpireMs > now) {
                    if (nextExpireMs != null) {
                        db.putWithExpire(key, value, nextExpireMs);
                    } else {
                        db.put(key, value);
                    }
                    loadedKeys++;
                }

                nextExpireMs = null;
            }

            // Verify trailing 8-byte checksum
            long expectedCrc = crc.getValue();
            long fileCrc = 0;
            for (int i = 7; i >= 0; i--) {
                int b = bin.read();
                if (b == -1) break;
                fileCrc |= ((long) b & 0xFF) << (i * 8);
            }

            if (fileCrc != 0 && fileCrc != expectedCrc) {
                System.err.println("Warning: RDB checksum mismatch (expected " + expectedCrc + ", got " + fileCrc + ")");
            }
        }

        return loadedKeys;
    }

    private static int readInt(InputStream in) throws IOException {
        int b1 = in.read(), b2 = in.read(), b3 = in.read(), b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new EOFException();
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    private static long readLong(InputStream in) throws IOException {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            int b = in.read();
            if (b == -1) throw new EOFException();
            v |= ((long) b & 0xFF) << (i * 8);
        }
        return v;
    }

    private static double readDouble(InputStream in) throws IOException {
        return Double.longBitsToDouble(readLong(in));
    }

    private static byte[] readByteArray(InputStream in) throws IOException {
        int len = readInt(in);
        if (len == -1) return null;
        byte[] b = in.readNBytes(len);
        if (b.length != len) throw new EOFException("Premature EOF reading byte array");
        return b;
    }
}
