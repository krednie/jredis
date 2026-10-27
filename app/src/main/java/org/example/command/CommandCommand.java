package org.example.command;

import org.example.protocol.Reply;
import org.example.server.ClientContext;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class CommandCommand implements Command {
    private final CommandRegistry registry;

    public CommandCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "COMMAND";
    }

    @Override
    public int arity() {
        return -1;
    }

    @Override
    public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
        if (args.size() > 1) {
            String sub = new String(args.get(1), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            if (sub.equals("COUNT")) {
                out.integer(registry.getAll().size());
                return;
            } else if (sub.equals("DOCS")) {
                // Return empty map or array for DOCS
                out.arrayHeader(0);
                return;
            }
        }

        Collection<Command> all = registry.getAll();
        out.arrayHeader(all.size());
        for (Command c : all) {
            out.arrayHeader(6);
            out.bulk(c.name().toLowerCase(Locale.ROOT));
            out.integer(c.arity());
            // flags
            out.arrayHeader(c.isReadOnly() ? 1 : 0);
            if (c.isReadOnly()) {
                out.simple("readonly");
            }
            out.integer(1); // first key
            out.integer(1); // last key
            out.integer(1); // step
        }
    }
}
