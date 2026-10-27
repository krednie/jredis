package org.example.command;

import org.example.oracle.InMemoryTarget;
import org.example.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CollectionsTest {

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
    void testListOperations() throws Exception {
        assertEquals(":2\r\n", exec("RPUSH", "mylist", "a", "b"));
        assertEquals(":3\r\n", exec("LPUSH", "mylist", "first"));
        assertEquals(":3\r\n", exec("LLEN", "mylist"));

        // LRANGE
        assertEquals("*3\r\n$5\r\nfirst\r\n$1\r\na\r\n$1\r\nb\r\n", exec("LRANGE", "mylist", "0", "-1"));

        // LINDEX
        assertEquals("$1\r\na\r\n", exec("LINDEX", "mylist", "1"));

        // LPOP & RPOP
        assertEquals("$5\r\nfirst\r\n", exec("LPOP", "mylist"));
        assertEquals("$1\r\nb\r\n", exec("RPOP", "mylist"));
        assertEquals(":1\r\n", exec("LLEN", "mylist"));

        // LSET
        assertEquals("+OK\r\n", exec("LSET", "mylist", "0", "updated"));
        assertEquals("$7\r\nupdated\r\n", exec("LINDEX", "mylist", "0"));

        // LREM
        exec("RPUSH", "mylist", "foo", "bar", "foo");
        assertEquals(":2\r\n", exec("LREM", "mylist", "0", "foo"));
        assertFalse(exec("LRANGE", "mylist", "0", "-1").contains("foo"));
    }

    @Test
    void testHashOperations() throws Exception {
        assertEquals(":2\r\n", exec("HSET", "user:100", "name", "John", "age", "30"));
        assertEquals("$4\r\nJohn\r\n", exec("HGET", "user:100", "name"));
        assertEquals(":1\r\n", exec("HEXISTS", "user:100", "name"));
        assertEquals(":2\r\n", exec("HLEN", "user:100"));

        // HINCRBY
        assertEquals(":35\r\n", exec("HINCRBY", "user:100", "age", "5"));

        // HMGET
        assertEquals("*2\r\n$4\r\nJohn\r\n$2\r\n35\r\n", exec("HMGET", "user:100", "name", "age"));

        // HDEL
        assertEquals(":1\r\n", exec("HDEL", "user:100", "age"));
        assertEquals(":1\r\n", exec("HLEN", "user:100"));
        assertEquals("$-1\r\n", exec("HGET", "user:100", "age"));
    }

    @Test
    void testSetOperations() throws Exception {
        assertEquals(":3\r\n", exec("SADD", "myset", "apple", "banana", "cherry"));
        assertEquals(":0\r\n", exec("SADD", "myset", "apple")); // already member
        assertEquals(":3\r\n", exec("SCARD", "myset"));
        assertEquals(":1\r\n", exec("SISMEMBER", "myset", "banana"));
        assertEquals(":0\r\n", exec("SISMEMBER", "myset", "orange"));

        assertEquals(":1\r\n", exec("SREM", "myset", "apple"));
        assertEquals(":2\r\n", exec("SCARD", "myset"));

        // Set ops
        exec("SADD", "setA", "memA", "memB", "memC");
        exec("SADD", "setB", "memB", "memC", "memD");

        String inter = exec("SINTER", "setA", "setB");
        assertTrue(inter.contains("memB") && inter.contains("memC") && !inter.contains("memA"));

        String union = exec("SUNION", "setA", "setB");
        assertTrue(union.contains("memA") && union.contains("memB") && union.contains("memC") && union.contains("memD"));

        String diff = exec("SDIFF", "setA", "setB");
        assertTrue(diff.contains("memA") && !diff.contains("memB") && !diff.contains("memC"));
    }

    @Test
    void testWrongTypeErrors() throws Exception {
        exec("SET", "str_key", "hello");
        assertTrue(exec("LPUSH", "str_key", "val").startsWith("-WRONGTYPE"));
        assertTrue(exec("HSET", "str_key", "f", "v").startsWith("-WRONGTYPE"));
        assertTrue(exec("SADD", "str_key", "m").startsWith("-WRONGTYPE"));

        exec("LPUSH", "list_key", "val");
        assertTrue(exec("GET", "list_key").startsWith("-WRONGTYPE"));
        assertTrue(exec("HGET", "list_key", "f").startsWith("-WRONGTYPE"));
    }
}
