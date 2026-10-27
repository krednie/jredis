package org.example.command;

import org.example.protocol.Reply;
import org.example.server.ClientContext;
import java.util.List;

public final class EchoCommand implements Command {

    @Override
    public String name() {
        return "ECHO";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
        out.bulk(args.get(1));
    }
}
