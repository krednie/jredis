package org.example.server;

import org.example.persist.AofWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EventLoopServerIntegrationTest {

    private Server server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        // Find free port
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }

        Server.Config config = new Server.Config(
                port,
                "127.0.0.1",
                16,
                0,
                "noeviction",
                false,
                AofWriter.FsyncPolicy.NO,
                "test-appendonly.aof",
                false,
                "test-dump.rdb",
                "build/tmp/test"
        );
        server = new Server(config);
        server.start();
        Thread.sleep(100); // small delay for event loop to bind
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private String sendAndReceive(String cmd) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(cmd.getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[4096];
            int read = in.read(buf);
            return read > 0 ? new String(buf, 0, read, StandardCharsets.UTF_8) : "";
        }
    }

    @Test
    void testPingPong() throws Exception {
        String reply = sendAndReceive("*1\r\n$4\r\nPING\r\n");
        assertEquals("+PONG\r\n", reply);
    }

    @Test
    void testEchoMessage() throws Exception {
        String reply = sendAndReceive("*2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n");
        assertEquals("$5\r\nhello\r\n", reply);
    }

    @Test
    void testInlinePing() throws Exception {
        String reply = sendAndReceive("PING\r\n");
        assertEquals("+PONG\r\n", reply);
    }

    @Test
    void testClientSetNameAndGetName() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[1024];

            // SETNAME
            out.write("*3\r\n$6\r\nCLIENT\r\n$7\r\nSETNAME\r\n$6\r\nmyconn\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            int r1 = in.read(buf);
            assertEquals("+OK\r\n", new String(buf, 0, r1, StandardCharsets.UTF_8));

            // GETNAME
            out.write("*2\r\n$6\r\nCLIENT\r\n$7\r\nGETNAME\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            int r2 = in.read(buf);
            assertEquals("$6\r\nmyconn\r\n", new String(buf, 0, r2, StandardCharsets.UTF_8));
        }
    }

    @Test
    void testPipelinedRequests() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String pipeline = "*1\r\n$4\r\nPING\r\n" +
                              "*2\r\n$4\r\nECHO\r\n$3\r\nfoo\r\n" +
                              "*1\r\n$4\r\nPING\r\n";
            out.write(pipeline.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String expected = "+PONG\r\n$3\r\nfoo\r\n+PONG\r\n";
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            byte[] buf = new byte[expectedBytes.length];
            int totalRead = 0;
            socket.setSoTimeout(2000);
            while (totalRead < expectedBytes.length) {
                int r = in.read(buf, totalRead, expectedBytes.length - totalRead);
                if (r == -1) break;
                totalRead += r;
            }
            String resp = new String(buf, 0, totalRead, StandardCharsets.UTF_8);
            assertEquals(expected, resp);
        }
    }
}
