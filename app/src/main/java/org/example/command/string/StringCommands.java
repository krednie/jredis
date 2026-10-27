package org.example.command.string;

import org.example.command.Command;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.ByteKey;
import org.example.store.RedisDb;
import org.example.store.Value;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class StringCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new SetCmd(serverContext));
        reg.register(new GetCmd(serverContext));
        reg.register(new DelCmd(serverContext));
        reg.register(new ExistsCmd(serverContext));
        reg.register(new TypeCmd(serverContext));
        reg.register(new IncrCmd(serverContext));
        reg.register(new DecrCmd(serverContext));
        reg.register(new IncrByCmd(serverContext));
        reg.register(new DecrByCmd(serverContext));
        reg.register(new AppendCmd(serverContext));
        reg.register(new StrlenCmd(serverContext));
        reg.register(new GetRangeCmd(serverContext));
        reg.register(new SetRangeCmd(serverContext));
        reg.register(new MGetCmd(serverContext));
        reg.register(new MSetCmd(serverContext));
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

    public static final class SetCmd implements Command {
        private final ServerContext sc;
        public SetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SET"; }
        @Override public int arity() { return -3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            byte[] valBytes = args.get(2);
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);

            Long expireMs = null;
            boolean nx = false;
            boolean xx = false;
            boolean getOld = false;

            for (int i = 3; i < args.size(); i++) {
                String opt = new String(args.get(i), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
                switch (opt) {
                    case "EX" -> {
                        if (++i >= args.size()) { out.error("ERR syntax error"); return; }
                        try {
                            long sec = Long.parseLong(new String(args.get(i), StandardCharsets.US_ASCII));
                            if (sec <= 0) { out.error("ERR invalid expire time in set"); return; }
                            expireMs = System.currentTimeMillis() + (sec * 1000);
                        } catch (NumberFormatException e) {
                            out.error("ERR value is not an integer or out of range");
                            return;
                        }
                    }
                    case "PX" -> {
                        if (++i >= args.size()) { out.error("ERR syntax error"); return; }
                        try {
                            long ms = Long.parseLong(new String(args.get(i), StandardCharsets.US_ASCII));
                            if (ms <= 0) { out.error("ERR invalid expire time in set"); return; }
                            expireMs = System.currentTimeMillis() + ms;
                        } catch (NumberFormatException e) {
                            out.error("ERR value is not an integer or out of range");
                            return;
                        }
                    }
                    case "NX" -> nx = true;
                    case "XX" -> xx = true;
                    case "GET" -> getOld = true;
                    default -> { out.error("ERR syntax error"); return; }
                }
            }

            if (nx && xx) {
                out.error("ERR syntax error");
                return;
            }

            boolean exists = db.exists(key);
            if (nx && exists) {
                out.nullBulk();
                return;
            }
            if (xx && !exists) {
                out.nullBulk();
                return;
            }

            byte[] oldBytes = null;
            if (getOld) {
                Value oldVal = db.get(key);
                if (oldVal != null) {
                    if (oldVal.getType() != Value.Type.STRING) {
                        out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                        return;
                    }
                    oldBytes = oldVal.asString();
                }
            }

            // Memory check before mutating
            if (sc.getMemoryTracker() != null && !sc.getMemoryTracker().checkAndEvict(sc.getKeyspace())) {
                out.error("OOM command not allowed when used memory > 'maxmemory'.");
                return;
            }

            Value newVal = new Value(Value.Type.STRING, valBytes);
            if (expireMs != null) {
                db.putWithExpire(key, newVal, expireMs);
            } else {
                db.put(key, newVal);
            }
            recordMutation(sc, ctx.dbIndex, args);

            if (getOld) {
                if (oldBytes != null) {
                    out.bulk(oldBytes);
                } else {
                    out.nullBulk();
                }
            } else {
                out.simple("OK");
            }
        }
    }

    public static final class GetCmd implements Command {
        private final ServerContext sc;
        public GetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "GET"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            if (val == null) {
                out.nullBulk();
                return;
            }
            if (val.getType() != Value.Type.STRING) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }
            out.bulk(val.asString());
        }
    }

    public static final class DelCmd implements Command {
        private final ServerContext sc;
        public DelCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "DEL"; }
        @Override public int arity() { return -2; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            int count = 0;
            for (int i = 1; i < args.size(); i++) {
                ByteKey key = ByteKey.of(args.get(i));
                if (db.del(key)) {
                    count++;
                }
            }
            if (count > 0) {
                recordMutation(sc, ctx.dbIndex, args);
            }
            out.integer(count);
        }
    }

    public static final class ExistsCmd implements Command {
        private final ServerContext sc;
        public ExistsCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "EXISTS"; }
        @Override public int arity() { return -2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            int count = 0;
            for (int i = 1; i < args.size(); i++) {
                ByteKey key = ByteKey.of(args.get(i));
                if (db.exists(key)) {
                    count++;
                }
            }
            out.integer(count);
        }
    }

    public static final class TypeCmd implements Command {
        private final ServerContext sc;
        public TypeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "TYPE"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            if (val == null) {
                out.simple("none");
            } else {
                out.simple(val.getType().getRedisName());
            }
        }
    }

    public static final class IncrCmd implements Command {
        private final ServerContext sc;
        public IncrCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "INCR"; }
        @Override public int arity() { return 2; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            applyIncr(sc, ctx, ByteKey.of(args.get(1)), 1, out, args);
        }
    }

    public static final class DecrCmd implements Command {
        private final ServerContext sc;
        public DecrCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "DECR"; }
        @Override public int arity() { return 2; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            applyIncr(sc, ctx, ByteKey.of(args.get(1)), -1, out, args);
        }
    }

    public static final class IncrByCmd implements Command {
        private final ServerContext sc;
        public IncrByCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "INCRBY"; }
        @Override public int arity() { return 3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            try {
                long delta = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
                applyIncr(sc, ctx, ByteKey.of(args.get(1)), delta, out, args);
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class DecrByCmd implements Command {
        private final ServerContext sc;
        public DecrByCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "DECRBY"; }
        @Override public int arity() { return 3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            try {
                long delta = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
                applyIncr(sc, ctx, ByteKey.of(args.get(1)), -delta, out, args);
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    private static void applyIncr(ServerContext sc, ClientContext ctx, ByteKey key, long delta, Reply out, List<byte[]> args) {
        RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
        Value val = db.get(key);
        long current = 0;
        if (val != null) {
            if (val.getType() != Value.Type.STRING) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }
            try {
                current = Long.parseLong(new String(val.asString(), StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
                return;
            }
        }

        long res = current + delta;
        byte[] newBytes = String.valueOf(res).getBytes(StandardCharsets.US_ASCII);
        if (val == null) {
            db.put(key, new Value(Value.Type.STRING, newBytes));
        } else {
            val.setData(newBytes);
            val.touch();
            db.touchKey(key);
        }
        recordMutation(sc, ctx.dbIndex, args);
        out.integer(res);
    }

    public static final class AppendCmd implements Command {
        private final ServerContext sc;
        public AppendCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "APPEND"; }
        @Override public int arity() { return 3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            byte[] appendBytes = args.get(2);
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);

            byte[] newBytes;
            if (val == null) {
                newBytes = appendBytes;
                db.put(key, new Value(Value.Type.STRING, newBytes));
            } else {
                if (val.getType() != Value.Type.STRING) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                byte[] old = val.asString();
                newBytes = new byte[old.length + appendBytes.length];
                System.arraycopy(old, 0, newBytes, 0, old.length);
                System.arraycopy(appendBytes, 0, newBytes, old.length, appendBytes.length);
                val.setData(newBytes);
                val.touch();
                db.touchKey(key);
            }
            recordMutation(sc, ctx.dbIndex, args);
            out.integer(newBytes.length);
        }
    }

    public static final class StrlenCmd implements Command {
        private final ServerContext sc;
        public StrlenCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "STRLEN"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.integer(0);
            } else if (val.getType() != Value.Type.STRING) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            } else {
                out.integer(val.asString().length);
            }
        }
    }

    public static final class GetRangeCmd implements Command {
        private final ServerContext sc;
        public GetRangeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "GETRANGE"; }
        @Override public int arity() { return 4; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.bulk(new byte[0]);
                return;
            }
            if (val.getType() != Value.Type.STRING) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }
            try {
                int start = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                int end = Integer.parseInt(new String(args.get(3), StandardCharsets.US_ASCII));
                byte[] b = val.asString();
                int len = b.length;
                if (start < 0) start = len + start;
                if (end < 0) end = len + end;
                if (start < 0) start = 0;
                if (end >= len) end = len - 1;
                if (start > end || start >= len) {
                    out.bulk(new byte[0]);
                } else {
                    byte[] slice = Arrays.copyOfRange(b, start, end + 1);
                    out.bulk(slice);
                }
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class SetRangeCmd implements Command {
        private final ServerContext sc;
        public SetRangeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SETRANGE"; }
        @Override public int arity() { return 4; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            int offset;
            try {
                offset = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                if (offset < 0) { out.error("ERR offset is out of range"); return; }
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
                return;
            }

            byte[] valBytes = args.get(3);
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            byte[] old = val == null ? new byte[0] : val.asString();
            if (val != null && val.getType() != Value.Type.STRING) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            int neededLen = Math.max(old.length, offset + valBytes.length);
            byte[] newBytes = new byte[neededLen];
            System.arraycopy(old, 0, newBytes, 0, old.length);
            System.arraycopy(valBytes, 0, newBytes, offset, valBytes.length);

            if (val == null) {
                db.put(key, new Value(Value.Type.STRING, newBytes));
            } else {
                val.setData(newBytes);
                val.touch();
                db.touchKey(key);
            }
            recordMutation(sc, ctx.dbIndex, args);
            out.integer(newBytes.length);
        }
    }

    public static final class MGetCmd implements Command {
        private final ServerContext sc;
        public MGetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "MGET"; }
        @Override public int arity() { return -2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            out.arrayHeader(args.size() - 1);
            for (int i = 1; i < args.size(); i++) {
                ByteKey key = ByteKey.of(args.get(i));
                Value val = db.get(key);
                if (val == null || val.getType() != Value.Type.STRING) {
                    out.nullBulk();
                } else {
                    out.bulk(val.asString());
                }
            }
        }
    }

    public static final class MSetCmd implements Command {
        private final ServerContext sc;
        public MSetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "MSET"; }
        @Override public int arity() { return -3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if ((args.size() - 1) % 2 != 0) {
                out.error("ERR wrong number of arguments for 'mset' command");
                return;
            }
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            for (int i = 1; i < args.size(); i += 2) {
                ByteKey key = ByteKey.of(args.get(i));
                byte[] val = args.get(i + 1);
                db.put(key, new Value(Value.Type.STRING, val));
            }
            recordMutation(sc, ctx.dbIndex, args);
            out.simple("OK");
        }
    }
}
