package org.example.command.types;

import org.example.command.Command;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.ByteKey;
import org.example.store.RedisDb;
import org.example.store.Value;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HashCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new HSetCmd(serverContext));
        reg.register(new HGetCmd(serverContext));
        reg.register(new HDelCmd(serverContext));
        reg.register(new HGetAllCmd(serverContext));
        reg.register(new HLenCmd(serverContext));
        reg.register(new HExistsCmd(serverContext));
        reg.register(new HIncrByCmd(serverContext));
        reg.register(new HMGetCmd(serverContext));
        reg.register(new HMSetCmd(serverContext));
        reg.register(new HKeysCmd(serverContext));
        reg.register(new HValsCmd(serverContext));
    }

    private static void recordMutation(ServerContext sc, int dbIndex, List<byte[]> args) {
        if (sc != null) {
            if (sc.getAofWriter() != null) sc.getAofWriter().append(dbIndex, args);
            if (sc.getRdbSnapshotter() != null) sc.getRdbSnapshotter().recordChange();
        }
    }

    public static final class HSetCmd implements Command {
        private final ServerContext sc;
        public HSetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HSET"; }
        @Override public int arity() { return -4; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if ((args.size() - 2) % 2 != 0) {
                out.error("ERR wrong number of arguments for 'hset' command");
                return;
            }

            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);

            Map<ByteKey, byte[]> map;
            if (val == null) {
                map = new HashMap<>();
                val = new Value(Value.Type.HASH, map);
                db.put(key, val);
            } else {
                if (val.getType() != Value.Type.HASH) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                map = val.asHash();
            }

            int added = 0;
            for (int i = 2; i < args.size(); i += 2) {
                ByteKey f = ByteKey.of(args.get(i));
                byte[] v = args.get(i + 1);
                if (map.put(f, v) == null) {
                    added++;
                }
            }
            val.touch();
            db.touchKey(key);
            recordMutation(sc, ctx.dbIndex, args);
            out.integer(added);
        }
    }

    public static final class HGetCmd implements Command {
        private final ServerContext sc;
        public HGetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HGET"; }
        @Override public int arity() { return 3; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            ByteKey field = ByteKey.of(args.get(2));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.nullBulk();
                return;
            }
            if (val.getType() != Value.Type.HASH) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }
            byte[] v = val.asHash().get(field);
            if (v == null) {
                out.nullBulk();
            } else {
                out.bulk(v);
            }
        }
    }

    public static final class HDelCmd implements Command {
        private final ServerContext sc;
        public HDelCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HDEL"; }
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
            if (val.getType() != Value.Type.HASH) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            Map<ByteKey, byte[]> map = val.asHash();
            int deleted = 0;
            for (int i = 2; i < args.size(); i++) {
                ByteKey field = ByteKey.of(args.get(i));
                if (map.remove(field) != null) {
                    deleted++;
                }
            }
            if (map.isEmpty()) {
                db.del(key);
            } else {
                val.touch();
                db.touchKey(key);
            }
            if (deleted > 0) recordMutation(sc, ctx.dbIndex, args);
            out.integer(deleted);
        }
    }

    public static final class HGetAllCmd implements Command {
        private final ServerContext sc;
        public HGetAllCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HGETALL"; }
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
            if (val.getType() != Value.Type.HASH) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            Map<ByteKey, byte[]> map = val.asHash();
            out.arrayHeader(map.size() * 2);
            for (Map.Entry<ByteKey, byte[]> e : map.entrySet()) {
                out.bulk(e.getKey().getBytes());
                out.bulk(e.getValue());
            }
        }
    }

    public static final class HLenCmd implements Command {
        private final ServerContext sc;
        public HLenCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HLEN"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.integer(0);
            } else if (val.getType() != Value.Type.HASH) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            } else {
                out.integer(val.asHash().size());
            }
        }
    }

    public static final class HExistsCmd implements Command {
        private final ServerContext sc;
        public HExistsCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HEXISTS"; }
        @Override public int arity() { return 3; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            ByteKey field = ByteKey.of(args.get(2));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null || val.getType() != Value.Type.HASH) {
                out.integer(0);
            } else {
                out.integer(val.asHash().containsKey(field) ? 1 : 0);
            }
        }
    }

    public static final class HIncrByCmd implements Command {
        private final ServerContext sc;
        public HIncrByCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HINCRBY"; }
        @Override public int arity() { return 4; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            ByteKey field = ByteKey.of(args.get(2));
            long delta;
            try {
                delta = Long.parseLong(new String(args.get(3), StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
                return;
            }

            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            Map<ByteKey, byte[]> map;
            if (val == null) {
                map = new HashMap<>();
                val = new Value(Value.Type.HASH, map);
                db.put(key, val);
            } else {
                if (val.getType() != Value.Type.HASH) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                map = val.asHash();
            }

            byte[] oldB = map.get(field);
            long cur = 0;
            if (oldB != null) {
                try {
                    cur = Long.parseLong(new String(oldB, StandardCharsets.US_ASCII));
                } catch (NumberFormatException e) {
                    out.error("ERR hash value is not an integer");
                    return;
                }
            }

            long res = cur + delta;
            map.put(field, String.valueOf(res).getBytes(StandardCharsets.US_ASCII));
            val.touch();
            db.touchKey(key);
            recordMutation(sc, ctx.dbIndex, args);
            out.integer(res);
        }
    }

    public static final class HMGetCmd implements Command {
        private final ServerContext sc;
        public HMGetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HMGET"; }
        @Override public int arity() { return -3; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val != null && val.getType() != Value.Type.HASH) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            Map<ByteKey, byte[]> map = val == null ? Map.of() : val.asHash();
            out.arrayHeader(args.size() - 2);
            for (int i = 2; i < args.size(); i++) {
                ByteKey f = ByteKey.of(args.get(i));
                byte[] v = map.get(f);
                if (v == null) {
                    out.nullBulk();
                } else {
                    out.bulk(v);
                }
            }
        }
    }

    public static final class HMSetCmd implements Command {
        private final ServerContext sc;
        public HMSetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HMSET"; }
        @Override public int arity() { return -4; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            new HSetCmd(sc).execute(ctx, args, out);
            out.reset();
            out.simple("OK");
        }
    }

    public static final class HKeysCmd implements Command {
        private final ServerContext sc;
        public HKeysCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HKEYS"; }
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
            if (val.getType() != Value.Type.HASH) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            Map<ByteKey, byte[]> map = val.asHash();
            out.arrayHeader(map.size());
            for (ByteKey k : map.keySet()) {
                out.bulk(k.getBytes());
            }
        }
    }

    public static final class HValsCmd implements Command {
        private final ServerContext sc;
        public HValsCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "HVALS"; }
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
            if (val.getType() != Value.Type.HASH) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            Collection<byte[]> values = val.asHash().values();
            out.arrayHeader(values.size());
            for (byte[] v : values) {
                out.bulk(v);
            }
        }
    }
}
