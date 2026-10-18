package org.example.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
        ascii("$" + b.length + "\r\n");
        buf.writeBytes(b);
        ascii("\r\n");
    }

    public byte[] toBytes() {
        return buf.toByteArray();
    }

}
