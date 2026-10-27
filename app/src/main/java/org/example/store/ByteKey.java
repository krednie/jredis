package org.example.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ByteKey implements Comparable<ByteKey> {
    private final byte[] bytes;
    private final int hash;

    public ByteKey(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null");
        }
        this.bytes = bytes;
        this.hash = Arrays.hashCode(bytes);
    }

    public static ByteKey of(byte[] bytes) {
        return new ByteKey(bytes);
    }

    public static ByteKey of(String s) {
        return new ByteKey(s.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] getBytes() {
        return bytes;
    }

    public String asString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ByteKey byteKey = (ByteKey) o;
        if (this.hash != byteKey.hash) return false;
        return Arrays.equals(bytes, byteKey.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int compareTo(ByteKey o) {
        int len1 = bytes.length;
        int len2 = o.bytes.length;
        int lim = Math.min(len1, len2);
        for (int k = 0; k < lim; k++) {
            int b1 = bytes[k] & 0xFF;
            int b2 = o.bytes[k] & 0xFF;
            if (b1 != b2) {
                return b1 - b2;
            }
        }
        return len1 - len2;
    }

    @Override
    public String toString() {
        return asString();
    }
}
