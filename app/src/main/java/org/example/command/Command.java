package org.example.command;

import org.example.protocol.Reply;
import org.example.server.ClientContext;
import java.util.List;

public interface Command {
    String name();

    int arity();

    void execute(ClientContext ctx, List<byte[]> args, Reply out);

    default boolean isReadOnly() {
        return false;
    }

    default boolean isWrite() {
        return false;
    }

    default boolean isPubSubAllowed() {
        return false;
    }

    default boolean isNoMulti() {
        return false;
    }
}