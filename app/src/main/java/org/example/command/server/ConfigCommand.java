package org.example.command.server;

import org.example.command.Command;
import org.example.evict.MemoryTracker;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.ByteKey;
import org.example.store.Value;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class ConfigCommand {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new ConfigCmd(serverContext));
        reg.register(new MemoryCmd(serverContext));
    }

    public static final class ConfigCmd implements Command {
        private final ServerContext sc;
        public ConfigCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "CONFIG"; }
        @Override public int arity() { return -2; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            String sub = new String(args.get(1), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            MemoryTracker tracker = sc.getMemoryTracker();

            if (sub.equals("GET")) {
                if (args.size() != 3) {
                    out.error("ERR wrong number of arguments for 'config|get' command");
                    return;
                }
                String param = new String(args.get(2), StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
                if (param.equals("maxmemory")) {
                    out.arrayHeader(2);
                    out.bulk("maxmemory");
                    out.bulk(String.valueOf(tracker.getMaxMemory()));
                } else if (param.equals("maxmemory-policy")) {
                    out.arrayHeader(2);
                    out.bulk("maxmemory-policy");
                    out.bulk(tracker.getEvictionPolicy().name());
                } else {
                    out.emptyArray();
                }
            } else if (sub.equals("SET")) {
                if (args.size() != 4) {
                    out.error("ERR wrong number of arguments for 'config|set' command");
                    return;
                }
                String param = new String(args.get(2), StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
                String val = new String(args.get(3), StandardCharsets.US_ASCII);

                if (param.equals("maxmemory")) {
                    try {
                        tracker.setMaxMemory(Long.parseLong(val));
                        out.simple("OK");
                    } catch (NumberFormatException e) {
                        out.error("ERR value is not an integer or out of range");
                    }
                } else if (param.equals("maxmemory-policy")) {
                    try {
                        tracker.setPolicyByName(val);
                        out.simple("OK");
                    } catch (IllegalArgumentException e) {
                        out.error("ERR invalid argument for 'maxmemory-policy'");
                    }
                } else {
                    out.error("ERR unsupported parameter '" + param + "'");
                }
            } else {
                out.error("ERR unknown subcommand '" + sub + "'");
            }
        }
    }

    public static final class MemoryCmd implements Command {
        private final ServerContext sc;
        public MemoryCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "MEMORY"; }
        @Override public int arity() { return -2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            String sub = new String(args.get(1), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            if (sub.equals("USAGE") && args.size() >= 3) {
                ByteKey key = ByteKey.of(args.get(2));
                Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
                if (val == null) {
                    out.nullBulk();
                } else {
                    out.integer(MemoryTracker.estimateEntrySize(key, val));
                }
            } else {
                out.error("ERR unknown subcommand for 'MEMORY'");
            }
        }
    }
}
