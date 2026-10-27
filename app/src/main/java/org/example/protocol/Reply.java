package org.example.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Reply {

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    private void ascii(String s) {
        buf.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    public void simple(String s) {
        ascii("+" + s + "\r\n");
    }

    public void error(String s) {
        ascii("-" + s + "\r\n");
    }

    public void integer(long n) {
        ascii(":" + n + "\r\n");
    }

    public void nullBulk() {
        ascii("$-1\r\n");
    }

    public void bulk(byte[] b) {
        if (b == null) {
            nullBulk();
            return;
        }
        ascii("$" + b.length + "\r\n");
        buf.writeBytes(b);
        ascii("\r\n");
    }

    public void bulk(String s) {
        if (s == null) {
            nullBulk();
        } else {
            bulk(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public void arrayHeader(int len) {
        ascii("*" + len + "\r\n");
    }

    public void nullArray() {
        ascii("*-1\r\n");
    }

    public void emptyArray() {
        ascii("*0\r\n");
    }

    public void stringArray(List<String> items) {
        if (items == null) {
            nullArray();
            return;
        }
        arrayHeader(items.size());
        for (String item : items) {
            bulk(item);
        }
    }

    public void bulkArray(List<byte[]> items) {
        if (items == null) {
            nullArray();
            return;
        }
        arrayHeader(items.size());
        for (byte[] item : items) {
            bulk(item);
        }
    }

    public void raw(byte[] b) {
        if (b != null && b.length > 0) {
            buf.writeBytes(b);
        }
    }

    public void reset() {
        buf.reset();
    }

    public int size() {
        return buf.size();
    }

    public boolean isEmpty() {
        return buf.size() == 0;
    }

    public byte[] toBytes() {
        return buf.toByteArray();
    }
}
