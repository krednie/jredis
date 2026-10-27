package org.example.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RespParser {

    public static final int MAX_BULK_LEN = 512 * 1024 * 1024; // 512MB
    public static final int MAX_INLINE_LEN = 64 * 1024; // 64KB

    /**
     * Attempts to parse a single command from the given ByteBuffer.
     * If a full command is parsed, the buffer's position is advanced past the command
     * and the list of argument byte arrays is returned.
     * If more data is needed, the buffer position is restored to where it was when this
     * method was called, and null is returned.
     *
     * @param buffer The input buffer in read mode (ready to get()).
     * @return List of command arguments, or null if incomplete.
     */
    public static List<byte[]> parse(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return null;
        }

        buffer.mark();
        byte first = buffer.get();

        if (first == '*') {
            // RESP array of bulk strings
            Long arrayLenObj = readLongLine(buffer);
            if (arrayLenObj == null) {
                buffer.reset();
                return null;
            }
            long arrayLen = arrayLenObj;
            if (arrayLen < 0) {
                return new ArrayList<>();
            }
            if (arrayLen > 1024 * 1024) {
                buffer.reset();
                throw new IllegalStateException("Protocol error: excessive array length " + arrayLen);
            }

            List<byte[]> args = new ArrayList<>((int) arrayLen);
            for (int i = 0; i < arrayLen; i++) {
                if (!buffer.hasRemaining()) {
                    buffer.reset();
                    return null;
                }
                byte prefix = buffer.get();
                if (prefix != '$') {
                    buffer.reset();
                    throw new IllegalStateException("Protocol error: expected '$', got '" + (char) prefix + "'");
                }
                Long bulkLenObj = readLongLine(buffer);
                if (bulkLenObj == null) {
                    buffer.reset();
                    return null;
                }
                long bulkLen = bulkLenObj;
                if (bulkLen == -1) {
                    args.add(null);
                    continue;
                }
                if (bulkLen < -1 || bulkLen > MAX_BULK_LEN) {
                    buffer.reset();
                    throw new IllegalStateException("Protocol error: invalid bulk length " + bulkLen);
                }

                int intBulkLen = (int) bulkLen;
                if (buffer.remaining() < intBulkLen + 2) {
                    buffer.reset();
                    return null;
                }

                byte[] data = new byte[intBulkLen];
                buffer.get(data);
                byte cr = buffer.get();
                byte lf = buffer.get();
                if (cr != '\r' || lf != '\n') {
                    buffer.reset();
                    throw new IllegalStateException("Protocol error: bulk string not terminated with CRLF");
                }
                args.add(data);
            }
            return args;
        } else {
            // Inline command (telnet style, or simple command like PING)
            buffer.reset();
            return parseInline(buffer);
        }
    }

    private static List<byte[]> parseInline(ByteBuffer buffer) {
        buffer.mark();
        int start = buffer.position();
        int crlfIndex = -1;
        int limit = buffer.limit();

        for (int i = start; i < limit; i++) {
            if (i - start > MAX_INLINE_LEN) {
                buffer.reset();
                throw new IllegalStateException("Protocol error: inline command too long");
            }
            if (buffer.get(i) == '\n') {
                crlfIndex = i;
                break;
            }
        }

        if (crlfIndex == -1) {
            buffer.reset();
            return null;
        }

        int end = crlfIndex;
        if (end > start && buffer.get(end - 1) == '\r') {
            end--;
        }

        byte[] lineBytes = new byte[end - start];
        buffer.get(lineBytes);

        // Advance past '\n'
        buffer.position(crlfIndex + 1);

        String line = new String(lineBytes, StandardCharsets.UTF_8).trim();
        if (line.isEmpty()) {
            // Empty line, parse next if available
            return parse(buffer);
        }

        // Split by whitespace
        String[] parts = line.split("\\s+");
        List<byte[]> args = new ArrayList<>(parts.length);
        for (String part : parts) {
            args.add(part.getBytes(StandardCharsets.UTF_8));
        }
        return args;
    }

    private static Long readLongLine(ByteBuffer buffer) {
        int start = buffer.position();
        int limit = buffer.limit();
        int crlfIndex = -1;

        for (int i = start; i < limit; i++) {
            if (buffer.get(i) == '\r') {
                if (i + 1 < limit && buffer.get(i + 1) == '\n') {
                    crlfIndex = i;
                    break;
                }
            }
        }

        if (crlfIndex == -1) {
            return null;
        }

        int len = crlfIndex - start;
        byte[] digits = new byte[len];
        buffer.get(digits);
        buffer.get(); // skip \r
        buffer.get(); // skip \n

        try {
            String s = new String(digits, StandardCharsets.US_ASCII);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Protocol error: invalid integer in RESP: " + e.getMessage());
        }
    }
}
