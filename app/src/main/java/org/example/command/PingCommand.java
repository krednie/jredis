package org.example.command;

import org.example.protocol.Reply;
import org.example.server.ClientContext;
import java.util.List;

public final class PingCommand implements Command {

    @Override
    public String name() {
        return "PING";
    }

    @Override
    public int arity() {
        return -1;
    }

    @Override
    public boolean isPubSubAllowed() {
        return true;
    }

    @Override
    public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
        if (args.size() > 2) {
            out.error("ERR wrong number of arguments for 'ping' command");
        } else if (args.size() == 2) {
            out.bulk(args.get(1));
        } else {
            out.simple("PONG");
        }
    }
}