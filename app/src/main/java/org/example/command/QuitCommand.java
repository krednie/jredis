package org.example.command;

import org.example.protocol.Reply;
import org.example.server.ClientContext;
import java.util.List;

public final class QuitCommand implements Command {

    @Override
    public String name() {
        return "QUIT";
    }

    @Override
    public int arity() {
        return 1;
    }

    @Override
    public boolean isPubSubAllowed() {
        return true;
    }

    @Override
    public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
        ctx.closeRequested = true;
        out.simple("OK");
    }
}
