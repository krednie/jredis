package org.example.command;

import org.example.oracle.InMemoryTarget;
import org.example.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class StringAndExpiryTest {

    private Server server;
    private InMemoryTarget target;

    @BeforeEach
    void setUp() {
        server = new Server();
        target = new InMemoryTarget(server.getRegistry());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    private String exec(String... args) throws Exception {
        byte[] reply = target.send(args);
        return new String(reply, StandardCharsets.UTF_8);
    }

    @Test
    void testBasicSetAndGet() throws Exception {
        assertEquals("+OK\r\n", exec("SET", "k1", "v1"));
        assertEquals("$2\r\nv1\r\n", exec("GET", "k1"));
        assertEquals("$-1\r\n", exec("GET", "k_missing"));
    }

    @Test
    void testSetOptionsNxXxGet() throws Exception {
        // NX when absent
        assertEquals("+OK\r\n", exec("SET", "lock", "val1", "NX"));
        // NX when present -> nil
        assertEquals("$-1\r\n", exec("SET", "lock", "val2", "NX"));
        assertEquals("$4\r\nval1\r\n", exec("GET", "lock"));

        // XX when present
        assertEquals("+OK\r\n", exec("SET", "lock", "val3", "XX"));
        assertEquals("$4\r\nval3\r\n", exec("GET", "lock"));
        // XX when absent -> nil
        assertEquals("$-1\r\n", exec("SET", "nonexistent", "val", "XX"));

        // GET option
        assertEquals("$4\r\nval3\r\n", exec("SET", "lock", "newval", "GET"));
        assertEquals("$6\r\nnewval\r\n", exec("GET", "lock"));
    }

    @Test
    void testDelAndExists() throws Exception {
        exec("SET", "a", "1");
        exec("SET", "b", "2");
        assertEquals(":2\r\n", exec("EXISTS", "a", "b", "c"));
        assertEquals(":1\r\n", exec("DEL", "a"));
        assertEquals(":0\r\n", exec("DEL", "a"));
        assertEquals(":1\r\n", exec("EXISTS", "b"));
    }

    @Test
    void testIncrDecr() throws Exception {
        assertEquals(":1\r\n", exec("INCR", "counter"));
        assertEquals(":2\r\n", exec("INCR", "counter"));
        assertEquals(":12\r\n", exec("INCRBY", "counter", "10"));
        assertEquals(":11\r\n", exec("DECR", "counter"));
        assertEquals(":6\r\n", exec("DECRBY", "counter", "5"));
    }

    @Test
    void testAppendStrlenGetSetRange() throws Exception {
        assertEquals(":5\r\n", exec("APPEND", "msg", "hello"));
        assertEquals(":11\r\n", exec("APPEND", "msg", " world"));
        assertEquals(":11\r\n", exec("STRLEN", "msg"));
        assertEquals("$5\r\nworld\r\n", exec("GETRANGE", "msg", "6", "10"));

        assertEquals(":11\r\n", exec("SETRANGE", "msg", "6", "redis"));
        assertEquals("$11\r\nhello redis\r\n", exec("GET", "msg"));
    }

    @Test
    void testMGetMSet() throws Exception {
        assertEquals("+OK\r\n", exec("MSET", "k1", "v1", "k2", "v2"));
        assertEquals("*3\r\n$2\r\nv1\r\n$2\r\nv2\r\n$-1\r\n", exec("MGET", "k1", "k2", "k3"));
    }

    @Test
    void testExpiryAndTtl() throws Exception {
        exec("SET", "temp", "val");
        assertEquals(":-1\r\n", exec("TTL", "temp"));
        assertEquals(":1\r\n", exec("EXPIRE", "temp", "100"));
        assertTrue(exec("TTL", "temp").startsWith(":"));

        assertEquals(":1\r\n", exec("PERSIST", "temp"));
        assertEquals(":-1\r\n", exec("TTL", "temp"));

        // Millisecond expiry
        exec("PEXPIRE", "temp", "10");
        Thread.sleep(25);
        assertEquals("$-1\r\n", exec("GET", "temp"));
        assertEquals(":-2\r\n", exec("TTL", "temp"));
    }

    @Test
    void testDbsizeAndFlushAll() throws Exception {
        exec("SET", "k1", "v1");
        exec("SET", "k2", "v2");
        assertEquals(":2\r\n", exec("DBSIZE"));
        assertEquals("+OK\r\n", exec("FLUSHALL"));
        assertEquals(":0\r\n", exec("DBSIZE"));
    }

    @Test
    void testKeysAndScan() throws Exception {
        exec("SET", "user:1", "alice");
        exec("SET", "user:2", "bob");
        exec("SET", "item:1", "sword");

        String keys = exec("KEYS", "user:*");
        assertTrue(keys.contains("user:1"));
        assertTrue(keys.contains("user:2"));
        assertFalse(keys.contains("item:1"));

        String scan = exec("SCAN", "0", "MATCH", "user:*");
        assertTrue(scan.contains("user:1") || scan.contains("user:2"));
    }
}
