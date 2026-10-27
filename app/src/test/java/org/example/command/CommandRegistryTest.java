package org.example.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.example.protocol.Reply;
import org.example.server.ClientContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {
    private final CommandRegistry reg = new CommandRegistry();

    CommandRegistryTest() {
        reg.register(new PingCommand());
    }

    private String run(String... parts) {
        List<byte[]> args = new ArrayList<>();
        for (String p : parts)
            args.add(p.getBytes(StandardCharsets.US_ASCII));
        Reply out = new Reply();
        reg.dispatch(new ClientContext(), args, out);
        return new String(out.toBytes(), StandardCharsets.US_ASCII);
    }

    @Test
    void pingReturnsPong() {
        assertEquals("+PONG\r\n", run("PING"));
    }

    @Test
    void pingEchoesMessage() {
        assertEquals("$5\r\nhello\r\n", run("PING", "hello"));
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertEquals("+PONG\r\n", run("pInG"));
    }

    @Test
    void tooManyArgsIsArityError() {
        assertEquals("-ERR wrong number of arguments for 'ping' command\r\n", run("PING", "a", "b"));
    }

    @Test
    void unknownCommand() {
        assertEquals("-ERR unknown command 'foo'\r\n", run("foo"));
    }
}