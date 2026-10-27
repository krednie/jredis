package org.example.command;

import org.example.oracle.InMemoryTarget;
import org.example.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PubSubAndTxnTest {

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
    void testBasicMultiExec() throws Exception {
        assertEquals("+OK\r\n", exec("MULTI"));
        assertEquals("+QUEUED\r\n", exec("SET", "foo", "10"));
        assertEquals("+QUEUED\r\n", exec("INCR", "foo"));
        assertEquals("+QUEUED\r\n", exec("GET", "foo"));

        String execReply = exec("EXEC");
        assertEquals("*3\r\n+OK\r\n:11\r\n$2\r\n11\r\n", execReply);
    }

    @Test
    void testMultiDiscard() throws Exception {
        assertEquals("+OK\r\n", exec("MULTI"));
        assertEquals("+QUEUED\r\n", exec("SET", "bar", "val"));
        assertEquals("+OK\r\n", exec("DISCARD"));

        // bar was never committed
        assertEquals("$-1\r\n", exec("GET", "bar"));
    }

    @Test
    void testWatchConflictAbortsExec() throws Exception {
        exec("SET", "balance", "100");

        // Client 1 watches balance
        assertEquals("+OK\r\n", exec("WATCH", "balance"));
        assertEquals("+OK\r\n", exec("MULTI"));
        assertEquals("+QUEUED\r\n", exec("INCRBY", "balance", "50"));

        // Another client mutates balance
        InMemoryTarget otherClient = new InMemoryTarget(server.getRegistry());
        otherClient.send("SET", "balance", "200");

        // Client 1 tries to EXEC -> must abort and return null array
        String execReply = exec("EXEC");
        assertEquals("*-1\r\n", execReply);

        // Balance should still be 200
        assertEquals("$3\r\n200\r\n", exec("GET", "balance"));
    }

    @Test
    void testSlowLog() throws Exception {
        // Set threshold to 0 to capture all commands
        server.getServerContext().getSlowLog().setSlowerThanMicros(0);

        exec("SET", "slowkey", "slowval");
        exec("GET", "slowkey");

        String slowlogLen = exec("SLOWLOG", "LEN");
        assertTrue(slowlogLen.startsWith(":"));

        String slowlogGet = exec("SLOWLOG", "GET", "2");
        assertTrue(slowlogGet.startsWith("*"));
    }
}
