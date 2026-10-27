package org.example.command.string;

import org.example.command.Command;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.ByteKey;
import org.example.store.RedisDb;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ExpiryCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new ExpireCmd(serverContext));
        reg.register(new PExpireCmd(serverContext));
        reg.register(new ExpireAtCmd(serverContext));
        reg.register(new PExpireAtCmd(serverContext));
        reg.register(new TtlCmd(serverContext));
        reg.register(new PTtlCmd(serverContext));
        reg.register(new PersistCmd(serverContext));
    }

    private static void recordMutation(ServerContext sc, int dbIndex, List<byte[]> args) {
        if (sc != null) {
            if (sc.getAofWriter() != null) {
                sc.getAofWriter().append(dbIndex, args);
            }
            if (sc.getRdbSnapshotter() != null) {
                sc.getRdbSnapshotter().recordChange();
            }
        }
    }

    public static final class ExpireCmd implements Command {
        private final ServerContext sc;
        public ExpireCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "EXPIRE"; }
        @Override public int arity() { return 3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            try {
                long sec = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
                long absMs = System.currentTimeMillis() + (sec * 1000);
                boolean ok = sc.getKeyspace().getDb(ctx.dbIndex).setExpire(key, absMs);
                if (ok) recordMutation(sc, ctx.dbIndex, args);
                out.integer(ok ? 1 : 0);
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class PExpireCmd implements Command {
        private final ServerContext sc;
        public PExpireCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "PEXPIRE"; }
        @Override public int arity() { return 3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            try {
                long ms = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
                long absMs = System.currentTimeMillis() + ms;
                boolean ok = sc.getKeyspace().getDb(ctx.dbIndex).setExpire(key, absMs);
                if (ok) recordMutation(sc, ctx.dbIndex, args);
                out.integer(ok ? 1 : 0);
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class ExpireAtCmd implements Command {
        private final ServerContext sc;
        public ExpireAtCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "EXPIREAT"; }
        @Override public int arity() { return 3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            try {
                long sec = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
                long absMs = sec * 1000;
                boolean ok = sc.getKeyspace().getDb(ctx.dbIndex).setExpire(key, absMs);
                if (ok) recordMutation(sc, ctx.dbIndex, args);
                out.integer(ok ? 1 : 0);
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class PExpireAtCmd implements Command {
        private final ServerContext sc;
        public PExpireAtCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "PEXPIREAT"; }
        @Override public int arity() { return 3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            try {
                long absMs = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
                boolean ok = sc.getKeyspace().getDb(ctx.dbIndex).setExpire(key, absMs);
                if (ok) recordMutation(sc, ctx.dbIndex, args);
                out.integer(ok ? 1 : 0);
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class TtlCmd implements Command {
        private final ServerContext sc;
        public TtlCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "TTL"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            long ttl = sc.getKeyspace().getDb(ctx.dbIndex).getTtl(key);
            out.integer(ttl);
        }
    }

    public static final class PTtlCmd implements Command {
        private final ServerContext sc;
        public PTtlCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "PTTL"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            long pttl = sc.getKeyspace().getDb(ctx.dbIndex).getPttl(key);
            out.integer(pttl);
        }
    }

    public static final class PersistCmd implements Command {
        private final ServerContext sc;
        public PersistCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "PERSIST"; }
        @Override public int arity() { return 2; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            boolean ok = sc.getKeyspace().getDb(ctx.dbIndex).persist(key);
            if (ok) recordMutation(sc, ctx.dbIndex, args);
            out.integer(ok ? 1 : 0);
        }
    }
}
