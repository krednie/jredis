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

public final class ListCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new LPushCmd(serverContext));
        reg.register(new RPushCmd(serverContext));
        reg.register(new LPopCmd(serverContext));
        reg.register(new RPopCmd(serverContext));
        reg.register(new LLenCmd(serverContext));
        reg.register(new LRangeCmd(serverContext));
        reg.register(new LIndexCmd(serverContext));
        reg.register(new LSetCmd(serverContext));
        reg.register(new LRemCmd(serverContext));
        reg.register(new LTrimCmd(serverContext));
    }

    private static void recordMutation(ServerContext sc, int dbIndex, List<byte[]> args) {
        if (sc != null) {
            if (sc.getAofWriter() != null) sc.getAofWriter().append(dbIndex, args);
            if (sc.getRdbSnapshotter() != null) sc.getRdbSnapshotter().recordChange();
        }
    }

    public static final class LPushCmd implements Command {
        private final ServerContext sc;
        public LPushCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "LPUSH"; }
        @Override public int arity() { return -3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);

            List<byte[]> list;
            if (val == null) {
                list = new ArrayList<>();
                val = new Value(Value.Type.LIST, list);
                db.put(key, val);
            } else {
                if (val.getType() != Value.Type.LIST) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                list = val.asList();
            }

            for (int i = 2; i < args.size(); i++) {
                list.add(0, args.get(i));
            }
            val.touch();
            db.touchKey(key);
            recordMutation(sc, ctx.dbIndex, args);
            out.integer(list.size());
        }
    }

    public static final class RPushCmd implements Command {
        private final ServerContext sc;
        public RPushCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "RPUSH"; }
        @Override public int arity() { return -3; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);

            List<byte[]> list;
            if (val == null) {
                list = new ArrayList<>();
                val = new Value(Value.Type.LIST, list);
                db.put(key, val);
            } else {
                if (val.getType() != Value.Type.LIST) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                list = val.asList();
            }

            for (int i = 2; i < args.size(); i++) {
                list.add(args.get(i));
            }
            val.touch();
            db.touchKey(key);
            recordMutation(sc, ctx.dbIndex, args);
            out.integer(list.size());
        }
    }

    public static final class LPopCmd implements Command {
        private final ServerContext sc;
        public LPopCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "LPOP"; }
        @Override public int arity() { return -2; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            if (val == null) {
                out.nullBulk();
                return;
            }
            if (val.getType() != Value.Type.LIST) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            List<byte[]> list = val.asList();
            if (list.isEmpty()) {
                db.del(key);
                out.nullBulk();
                return;
            }

            if (args.size() == 2) {
                byte[] popped = list.remove(0);
                if (list.isEmpty()) db.del(key);
                else { val.touch(); db.touchKey(key); }
                recordMutation(sc, ctx.dbIndex, args);
                out.bulk(popped);
            } else {
                int count;
                try {
                    count = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                } catch (NumberFormatException e) {
                    out.error("ERR value is not an integer or out of range");
                    return;
                }
                List<byte[]> poppedList = new ArrayList<>();
                while (!list.isEmpty() && poppedList.size() < count) {
                    poppedList.add(list.remove(0));
                }
                if (list.isEmpty()) db.del(key);
                else { val.touch(); db.touchKey(key); }
                recordMutation(sc, ctx.dbIndex, args);
                out.bulkArray(poppedList);
            }
        }
    }

    public static final class RPopCmd implements Command {
        private final ServerContext sc;
        public RPopCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "RPOP"; }
        @Override public int arity() { return -2; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            if (val == null) {
                out.nullBulk();
                return;
            }
            if (val.getType() != Value.Type.LIST) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            List<byte[]> list = val.asList();
            if (list.isEmpty()) {
                db.del(key);
                out.nullBulk();
                return;
            }

            if (args.size() == 2) {
                byte[] popped = list.remove(list.size() - 1);
                if (list.isEmpty()) db.del(key);
                else { val.touch(); db.touchKey(key); }
                recordMutation(sc, ctx.dbIndex, args);
                out.bulk(popped);
            } else {
                int count;
                try {
                    count = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                } catch (NumberFormatException e) {
                    out.error("ERR value is not an integer or out of range");
                    return;
                }
                List<byte[]> poppedList = new ArrayList<>();
                while (!list.isEmpty() && poppedList.size() < count) {
                    poppedList.add(list.remove(list.size() - 1));
                }
                if (list.isEmpty()) db.del(key);
                else { val.touch(); db.touchKey(key); }
                recordMutation(sc, ctx.dbIndex, args);
                out.bulkArray(poppedList);
            }
        }
    }

    public static final class LLenCmd implements Command {
        private final ServerContext sc;
        public LLenCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "LLEN"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.integer(0);
            } else if (val.getType() != Value.Type.LIST) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            } else {
                out.integer(val.asList().size());
            }
        }
    }

    public static final class LRangeCmd implements Command {
        private final ServerContext sc;
        public LRangeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "LRANGE"; }
        @Override public int arity() { return 4; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.emptyArray();
                return;
            }
            if (val.getType() != Value.Type.LIST) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            try {
                int start = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                int stop = Integer.parseInt(new String(args.get(3), StandardCharsets.US_ASCII));
                List<byte[]> list = val.asList();
                int len = list.size();
                if (start < 0) start = len + start;
                if (stop < 0) stop = len + stop;
                if (start < 0) start = 0;
                if (start >= len || start > stop) {
                    out.emptyArray();
                    return;
                }
                if (stop >= len) stop = len - 1;

                List<byte[]> sub = list.subList(start, stop + 1);
                out.bulkArray(sub);
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class LIndexCmd implements Command {
        private final ServerContext sc;
        public LIndexCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "LINDEX"; }
        @Override public int arity() { return 3; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.nullBulk();
                return;
            }
            if (val.getType() != Value.Type.LIST) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            try {
                int index = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                List<byte[]> list = val.asList();
                int len = list.size();
                if (index < 0) index = len + index;
                if (index < 0 || index >= len) {
                    out.nullBulk();
                } else {
                    out.bulk(list.get(index));
                }
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class LSetCmd implements Command {
        private final ServerContext sc;
        public LSetCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "LSET"; }
        @Override public int arity() { return 4; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            if (val == null) {
                out.error("ERR no such key");
                return;
            }
            if (val.getType() != Value.Type.LIST) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            try {
                int index = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                List<byte[]> list = val.asList();
                int len = list.size();
                if (index < 0) index = len + index;
                if (index < 0 || index >= len) {
                    out.error("ERR index out of range");
                    return;
                }
                list.set(index, args.get(3));
                val.touch();
                db.touchKey(key);
                recordMutation(sc, ctx.dbIndex, args);
                out.simple("OK");
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class LRemCmd implements Command {
        private final ServerContext sc;
        public LRemCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "LREM"; }
        @Override public int arity() { return 4; }
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
            if (val.getType() != Value.Type.LIST) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            try {
                int count = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                byte[] target = args.get(3);
                List<byte[]> list = val.asList();
                int removed = 0;

                if (count == 0) {
                    Iterator<byte[]> it = list.iterator();
                    while (it.hasNext()) {
                        if (Arrays.equals(it.next(), target)) {
                            it.remove();
                            removed++;
                        }
                    }
                } else if (count > 0) {
                    Iterator<byte[]> it = list.iterator();
                    while (it.hasNext() && removed < count) {
                        if (Arrays.equals(it.next(), target)) {
                            it.remove();
                            removed++;
                        }
                    }
                } else {
                    int absCount = Math.abs(count);
                    for (int i = list.size() - 1; i >= 0 && removed < absCount; i--) {
                        if (Arrays.equals(list.get(i), target)) {
                            list.remove(i);
                            removed++;
                        }
                    }
                }

                if (list.isEmpty()) db.del(key);
                else { val.touch(); db.touchKey(key); }
                if (removed > 0) recordMutation(sc, ctx.dbIndex, args);
                out.integer(removed);
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }

    public static final class LTrimCmd implements Command {
        private final ServerContext sc;
        public LTrimCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "LTRIM"; }
        @Override public int arity() { return 4; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            if (val == null) {
                out.simple("OK");
                return;
            }
            if (val.getType() != Value.Type.LIST) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            try {
                int start = Integer.parseInt(new String(args.get(2), StandardCharsets.US_ASCII));
                int stop = Integer.parseInt(new String(args.get(3), StandardCharsets.US_ASCII));
                List<byte[]> list = val.asList();
                int len = list.size();
                if (start < 0) start = len + start;
                if (stop < 0) stop = len + stop;
                if (start < 0) start = 0;
                if (start >= len || start > stop) {
                    db.del(key);
                } else {
                    if (stop >= len) stop = len - 1;
                    List<byte[]> trimmed = new ArrayList<>(list.subList(start, stop + 1));
                    val.setData(trimmed);
                    val.touch();
                    db.touchKey(key);
                }
                recordMutation(sc, ctx.dbIndex, args);
                out.simple("OK");
            } catch (NumberFormatException e) {
                out.error("ERR value is not an integer or out of range");
            }
        }
    }
}
