package org.example.command.server;

import org.example.command.Command;
import org.example.obs.SlowLog;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class SlowLogCommand implements Command {
    private final ServerContext sc;

    public SlowLogCommand(ServerContext sc) {
        this.sc = sc;
    }

    @Override
    public String name() {
        return "SLOWLOG";
    }

    @Override
    public int arity() {
        return -2;
    }

    @Override
    public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
        SlowLog slowLog = sc.getSlowLog();
        if (slowLog == null) {
            out.emptyArray();
            return;
        }

        String sub = new String(args.get(1), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "GET" -> {
                int count = 10;
                if (args.size() > 2) {
                    try {
                        count = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                    } catch (NumberFormatException ignored) {}
                }
                List<SlowLog.Entry> entries = slowLog.get(count);
                out.arrayHeader(entries.size());
                for (SlowLog.Entry e : entries) {
                    out.arrayHeader(4);
                    out.integer(e.id());
                    out.integer(e.timestampSeconds());
                    out.integer(e.durationMicros());
                    out.bulkArray(e.args());
                }
            }
            case "LEN" -> out.integer(slowLog.len());
            case "RESET" -> {
                slowLog.reset();
                out.simple("OK");
            }
            default -> out.error("ERR unknown subcommand or wrong number of arguments for '" + sub + "'.");
        }
    }
}
