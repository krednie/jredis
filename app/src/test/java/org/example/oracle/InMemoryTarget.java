package org.example.oracle;

import java.util.List;
import org.example.server.ClientContext;
import org.example.command.CommandRegistry;
import org.example.protocol.Reply;

public final class InMemoryTarget implements CommandTarget {
    private final CommandRegistry registry;
    private final ClientContext ctx = new ClientContext();

    public InMemoryTarget(CommandRegistry registry) {
        this.registry = registry;
    }

    public byte[] send(List<byte[]> args) {
        Reply out = new Reply();
        registry.dispatch(ctx, args, out);
        return out.toBytes();
    }
}
