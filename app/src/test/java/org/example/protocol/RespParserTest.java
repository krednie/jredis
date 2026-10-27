package org.example.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespParserTest {

    @Test
    void parseSimplePingCommand() {
        byte[] raw = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        List<byte[]> args = RespParser.parse(buf);

        assertNotNull(args);
        assertEquals(1, args.size());
        assertEquals("PING", new String(args.get(0), StandardCharsets.US_ASCII));
        assertEquals(raw.length, buf.position());
    }

    @Test
    void parseMultiArgCommand() {
        byte[] raw = "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        List<byte[]> args = RespParser.parse(buf);

        assertNotNull(args);
        assertEquals(3, args.size());
        assertEquals("SET", new String(args.get(0), StandardCharsets.US_ASCII));
        assertEquals("foo", new String(args.get(1), StandardCharsets.US_ASCII));
        assertEquals("bar", new String(args.get(2), StandardCharsets.US_ASCII));
    }

    @Test
    void parsePipelinedCommands() {
        byte[] raw = ("*1\r\n$4\r\nPING\r\n" +
                      "*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(raw);

        List<byte[]> cmd1 = RespParser.parse(buf);
        assertNotNull(cmd1);
        assertEquals("PING", new String(cmd1.get(0), StandardCharsets.US_ASCII));

        List<byte[]> cmd2 = RespParser.parse(buf);
        assertNotNull(cmd2);
        assertEquals("ECHO", new String(cmd2.get(0), StandardCharsets.US_ASCII));
        assertEquals("hi", new String(cmd2.get(1), StandardCharsets.US_ASCII));
        assertFalse(buf.hasRemaining());
    }

    @Test
    void parseInlineCommand() {
        byte[] raw = "PING\r\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        List<byte[]> args = RespParser.parse(buf);

        assertNotNull(args);
        assertEquals(1, args.size());
        assertEquals("PING", new String(args.get(0), StandardCharsets.US_ASCII));
    }

    @Test
    void parseInlineCommandWithArgs() {
        byte[] raw = "SET mykey myval\r\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        List<byte[]> args = RespParser.parse(buf);

        assertNotNull(args);
        assertEquals(3, args.size());
        assertEquals("SET", new String(args.get(0), StandardCharsets.US_ASCII));
        assertEquals("mykey", new String(args.get(1), StandardCharsets.US_ASCII));
        assertEquals("myval", new String(args.get(2), StandardCharsets.US_ASCII));
    }

    @Test
    void partialReadChunkBoundaryFuzzing() {
        byte[] raw = "*3\r\n$3\r\nSET\r\n$5\r\nhello\r\n$5\r\nworld\r\n".getBytes(StandardCharsets.US_ASCII);

        // Slice at every possible index from 1 to raw.length - 1
        for (int split = 1; split < raw.length; split++) {
            ByteBuffer buf = ByteBuffer.allocate(raw.length);
            // Put first chunk
            buf.put(raw, 0, split);
            buf.flip();

            // Attempt parse on partial chunk: must return null and keep position 0
            List<byte[]> partial = RespParser.parse(buf);
            assertNull(partial, "Expected null on partial frame split at " + split);
            assertEquals(0, buf.position());

            // Compact and append the rest
            buf.compact();
            buf.put(raw, split, raw.length - split);
            buf.flip();

            // Now parse must succeed completely
            List<byte[]> full = RespParser.parse(buf);
            assertNotNull(full, "Expected complete parse after appending remaining bytes for split at " + split);
            assertEquals(3, full.size());
            assertEquals("SET", new String(full.get(0), StandardCharsets.US_ASCII));
            assertEquals("hello", new String(full.get(1), StandardCharsets.US_ASCII));
            assertEquals("world", new String(full.get(2), StandardCharsets.US_ASCII));
        }
    }
}
