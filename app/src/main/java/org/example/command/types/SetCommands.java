package org.example.command.types;

import org.example.command.Command;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.ByteKey;
import org.example.store.RedisDb;
import org.example.store.Value;

import java.util.*;

public final class SetCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new SAddCmd(serverContext));
        reg.register(new SRemCmd(serverContext));
        reg.register(new SIsMemberCmd(serverContext));
        reg.register(new SMembersCmd(serverContext));
        reg.register(new SCardCmd(serverContext));
        reg.register(new SInterCmd(serverContext));
        reg.register(new SUnionCmd(serverContext));
        reg.register(new SDiffCmd(serverContext));
    }

    private static void recordMutation(ServerContext sc, int dbIndex, List<byte[]> args) {
        if (sc != null) {
            if (sc.getAofWriter() != null) sc.getAofWriter().append(dbIndex, args);
            if (sc.getRdbSnapshotter() != null) sc.getRdbSnapshotter().recordChange();
        }
    }

    public static final class SAddCmd implements Command {
        private final ServerContext sc;
        public SAddCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SADD"; }
        @Override public int arity() { return -3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);

            Set<ByteKey> set;
            if (val == null) {
                set = new HashSet<>();
                val = new Value(Value.Type.SET, set);
                db.put(key, val);
            } else {
                if (val.getType() != Value.Type.SET) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                set = val.asSet();
            }

            int added = 0;
            for (int i = 2; i < args.size(); i++) {
                ByteKey member = ByteKey.of(args.get(i));
                if (set.add(member)) {
                    added++;
                }
            }
            val.touch();
            db.touchKey(key);
            recordMutation(sc, ctx.dbIndex, args);
            out.integer(added);
        }
    }

    public static final class SRemCmd implements Command {
        private final ServerContext sc;
        public SRemCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SREM"; }
        @Override public int arity() { return -3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            if (val == null) {
                out.integer(0);
                return;
            }
            if (val.getType() != Value.Type.SET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            Set<ByteKey> set = val.asSet();
            int removed = 0;
            for (int i = 2; i < args.size(); i++) {
                ByteKey member = ByteKey.of(args.get(i));
                if (set.remove(member)) {
                    removed++;
                }
            }
            if (set.isEmpty()) {
                db.del(key);
            } else {
                val.touch();
                db.touchKey(key);
            }
            if (removed > 0) recordMutation(sc, ctx.dbIndex, args);
            out.integer(removed);
        }
    }

    public static final class SIsMemberCmd implements Command {
        private final ServerContext sc;
        public SIsMemberCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SISMEMBER"; }
        @Override public int arity() { return 3; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            ByteKey member = ByteKey.of(args.get(2));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.integer(0);
            } else if (val.getType() != Value.Type.SET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            } else {
                out.integer(val.asSet().contains(member) ? 1 : 0);
            }
        }
    }

    public static final class SMembersCmd implements Command {
        private final ServerContext sc;
        public SMembersCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SMEMBERS"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.emptyArray();
                return;
            }
            if (val.getType() != Value.Type.SET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            Set<ByteKey> set = val.asSet();
            out.arrayHeader(set.size());
            for (ByteKey m : set) {
                out.bulk(m.getBytes());
            }
        }
    }

    public static final class SCardCmd implements Command {
        private final ServerContext sc;
        public SCardCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SCARD"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.integer(0);
            } else if (val.getType() != Value.Type.SET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            } else {
                out.integer(val.asSet().size());
            }
        }
    }

    public static final class SInterCmd implements Command {
        private final ServerContext sc;
        public SInterCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SINTER"; }
        @Override public int arity() { return -2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Set<ByteKey> result = null;

            for (int i = 1; i < args.size(); i++) {
                ByteKey key = ByteKey.of(args.get(i));
                Value val = db.get(key);
                if (val == null) {
                    out.emptyArray();
                    return;
                }
                if (val.getType() != Value.Type.SET) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                if (result == null) {
                    result = new HashSet<>(val.asSet());
                } else {
                    result.retainAll(val.asSet());
                }
                if (result.isEmpty()) {
                    break;
                }
            }

            if (result == null || result.isEmpty()) {
                out.emptyArray();
            } else {
                out.arrayHeader(result.size());
                for (ByteKey k : result) {
                    out.bulk(k.getBytes());
                }
            }
        }
    }

    public static final class SUnionCmd implements Command {
        private final ServerContext sc;
        public SUnionCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SUNION"; }
        @Override public int arity() { return -2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Set<ByteKey> result = new HashSet<>();

            for (int i = 1; i < args.size(); i++) {
                ByteKey key = ByteKey.of(args.get(i));
                Value val = db.get(key);
                if (val == null) continue;
                if (val.getType() != Value.Type.SET) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                result.addAll(val.asSet());
            }

            out.arrayHeader(result.size());
            for (ByteKey k : result) {
                out.bulk(k.getBytes());
            }
        }
    }

    public static final class SDiffCmd implements Command {
        private final ServerContext sc;
        public SDiffCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SDIFF"; }
        @Override public int arity() { return -2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            ByteKey firstKey = ByteKey.of(args.get(1));
            Value firstVal = db.get(firstKey);
            if (firstVal == null) {
                out.emptyArray();
                return;
            }
            if (firstVal.getType() != Value.Type.SET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            Set<ByteKey> result = new HashSet<>(firstVal.asSet());
            for (int i = 2; i < args.size(); i++) {
                ByteKey key = ByteKey.of(args.get(i));
                Value val = db.get(key);
                if (val == null) continue;
                if (val.getType() != Value.Type.SET) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                result.removeAll(val.asSet());
            }

            out.arrayHeader(result.size());
            for (ByteKey k : result) {
                out.bulk(k.getBytes());
            }
        }
    }
}
