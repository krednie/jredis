package org.example.command;

import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.Connection;
import org.example.server.ServerContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class ClientCommand implements Command {
    private final ServerContext serverContext;

    public ClientCommand(ServerContext serverContext) {
        this.serverContext = serverContext;
    }

    @Override
    public String name() {
        return "CLIENT";
    }

    @Override
    public int arity() {
        return -2;
    }

    @Override
    public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
        String sub = new String(args.get(1), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "SETNAME" -> {
                if (args.size() != 3) {
                    out.error("ERR syntax error");
                    return;
                }
                ctx.name = new String(args.get(2), StandardCharsets.UTF_8);
                out.simple("OK");
            }
            case "GETNAME" -> {
                if (ctx.name == null || ctx.name.isEmpty()) {
                    out.nullBulk();
                } else {
                    out.bulk(ctx.name);
                }
            }
            case "ID" -> out.integer(ctx.id);
            case "LIST" -> {
                StringBuilder sb = new StringBuilder();
                if (serverContext != null) {
                    for (Connection conn : serverContext.getActiveConnections()) {
                        ClientContext c = conn.getContext();
                        sb.append("id=").append(c.id)
                          .append(" addr=127.0.0.1:0")
                          .append(" name=").append(c.name)
                          .append(" db=").append(c.dbIndex)
                          .append("\n");
                    }
                }
                out.bulk(sb.toString());
            }
            case "INFO" -> {
                String info = "id=" + ctx.id + " addr=127.0.0.1:0 name=" + ctx.name + " db=" + ctx.dbIndex + "\n";
                out.bulk(info);
            }
            default -> out.error("ERR unknown subcommand or wrong number of arguments for '" + sub + "'.");
        }
    }
}
