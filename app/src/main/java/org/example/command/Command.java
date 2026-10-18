package org.example.command;

import org.example.protocol.Reply;
import org.example.server.ClientContext;
import java.util.List;

public interface Command {
    String name();

    int arity();

    void execute(ClientContext ctx, List<byte[]> args, Reply out);
}