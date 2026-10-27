package org.example.command.server;

import org.example.command.Command;
import org.example.pubsub.PubSubManager;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.ByteKey;
import org.example.store.RedisDb;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class DbCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new SelectCmd(serverContext));
        reg.register(new DbsizeCmd(serverContext));
        reg.register(new FlushDbCmd(serverContext));
        reg.register(new FlushAllCmd(serverContext));
        reg.register(new KeysCmd(serverContext));
        reg.register(new ScanCmd(serverContext));
        reg.register(new TimeCmd());
    }

    public static final class SelectCmd implements Command {
        private final ServerContext sc;
        public SelectCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SELECT"; }
        @Override public int arity() { return 2; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            try {
                int dbIndex = Integer.parseInt(new String(args.get(1), StandardCharsets.US_ASCII));
                sc.getKeyspace().getDb(dbIndex); // validates index
                ctx.dbIndex = dbIndex;
                out.simple("OK");
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                out.error("ERR DB index is out of range");
            }
        }
    }

    public static final class DbsizeCmd implements Command {
        private final ServerContext sc;
        public DbsizeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "DBSIZE"; }
        @Override public int arity() { return 1; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            out.integer(sc.getKeyspace().dbsize(ctx.dbIndex));
        }
    }

    public static final class FlushDbCmd implements Command {
        private final ServerContext sc;
        public FlushDbCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "FLUSHDB"; }
        @Override public int arity() { return -1; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            sc.getKeyspace().flushDb(ctx.dbIndex);
            if (sc.getAofWriter() != null) sc.getAofWriter().append(ctx.dbIndex, args);
            if (sc.getRdbSnapshotter() != null) sc.getRdbSnapshotter().recordChange();
            out.simple("OK");
        }
    }

    public static final class FlushAllCmd implements Command {
        private final ServerContext sc;
        public FlushAllCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "FLUSHALL"; }
        @Override public int arity() { return -1; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            sc.getKeyspace().flushAll();
            if (sc.getAofWriter() != null) sc.getAofWriter().append(ctx.dbIndex, args);
            if (sc.getRdbSnapshotter() != null) sc.getRdbSnapshotter().recordChange();
            out.simple("OK");
        }
    }

    public static final class KeysCmd implements Command {
        private final ServerContext sc;
        public KeysCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "KEYS"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            String pattern = new String(args.get(1), StandardCharsets.UTF_8);
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            List<byte[]> matched = new ArrayList<>();
            for (ByteKey k : db.keySet()) {
                if (pattern.equals("*") || PubSubManager.matchesPattern(pattern, k.asString())) {
                    matched.add(k.getBytes());
                }
            }
            out.bulkArray(matched);
        }
    }

    public static final class ScanCmd implements Command {
        private final ServerContext sc;
        public ScanCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SCAN"; }
        @Override public int arity() { return -2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            int cursor;
            try {
                cursor = Integer.parseInt(new String(args.get(1), StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
                return;
            }

            String match = "*";
            int count = 10;
            for (int i = 2; i < args.size(); i++) {
                String opt = new String(args.get(i), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
                if (opt.equals("MATCH") && i + 1 < args.size()) {
                    match = new String(args.get(++i), StandardCharsets.UTF_8);
                } else if (opt.equals("COUNT") && i + 1 < args.size()) {
                    try {
                        count = Integer.parseInt(new String(args.get(++i), StandardCharsets.US_ASCII));
                    } catch (NumberFormatException ignored) {}
                }
            }

            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            List<ByteKey> allKeys = new ArrayList<>(db.keySet());
            int total = allKeys.size();
            List<byte[]> result = new ArrayList<>();

            int nextCursor = 0;
            if (cursor < total) {
                int end = Math.min(cursor + count, total);
                for (int i = cursor; i < end; i++) {
                    ByteKey k = allKeys.get(i);
                    if (match.equals("*") || PubSubManager.matchesPattern(match, k.asString())) {
                        result.add(k.getBytes());
                    }
                }
                nextCursor = end >= total ? 0 : end;
            }

            out.arrayHeader(2);
            out.bulk(String.valueOf(nextCursor));
            out.bulkArray(result);
        }
    }

    public static final class TimeCmd implements Command {
        @Override public String name() { return "TIME"; }
        @Override public int arity() { return 1; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            long nowMs = System.currentTimeMillis();
            long sec = nowMs / 1000;
            long micro = (nowMs % 1000) * 1000;
            out.arrayHeader(2);
            out.bulk(String.valueOf(sec));
            out.bulk(String.valueOf(micro));
        }
    }
}
