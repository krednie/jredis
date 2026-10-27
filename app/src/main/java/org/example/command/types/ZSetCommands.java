package org.example.command.types;

import org.example.command.Command;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.ByteKey;
import org.example.store.RedisDb;
import org.example.store.Value;
import org.example.types.RZSet;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ZSetCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new ZAddCmd(serverContext));
        reg.register(new ZRemCmd(serverContext));
        reg.register(new ZScoreCmd(serverContext));
        reg.register(new ZRankCmd(serverContext));
        reg.register(new ZRevRankCmd(serverContext));
        reg.register(new ZRangeCmd(serverContext));
        reg.register(new ZRevRangeCmd(serverContext));
        reg.register(new ZRangeByScoreCmd(serverContext));
        reg.register(new ZIncrByCmd(serverContext));
        reg.register(new ZCardCmd(serverContext));
        reg.register(new ZCountCmd(serverContext));
    }

    private static void recordMutation(ServerContext sc, int dbIndex, List<byte[]> args) {
        if (sc != null) {
            if (sc.getAofWriter() != null) sc.getAofWriter().append(dbIndex, args);
            if (sc.getRdbSnapshotter() != null) sc.getRdbSnapshotter().recordChange();
        }
    }

    public static final class ZAddCmd implements Command {
        private final ServerContext sc;
        public ZAddCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZADD"; }
        @Override public int arity() { return -4; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if ((args.size() - 2) % 2 != 0) {
                out.error("ERR syntax error");
                return;
            }

            ByteKey key = ByteKey.of(args.get(1));
            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);

            RZSet zset;
            if (val == null) {
                zset = new RZSet();
                val = new Value(Value.Type.ZSET, zset);
                db.put(key, val);
            } else {
                if (val.getType() != Value.Type.ZSET) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                zset = val.asZSet();
            }

            int added = 0;
            for (int i = 2; i < args.size(); i += 2) {
                double score;
                try {
                    score = Double.parseDouble(new String(args.get(i), StandardCharsets.US_ASCII));
                } catch (NumberFormatException e) {
                    out.error("ERR value is not a valid float");
                    return;
                }
                ByteKey member = ByteKey.of(args.get(i + 1));
                if (zset.add(score, member) == 1) {
                    added++;
                }
            }
            val.touch();
            db.touchKey(key);
            recordMutation(sc, ctx.dbIndex, args);
            out.integer(added);
        }
    }

    public static final class ZRemCmd implements Command {
        private final ServerContext sc;
        public ZRemCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZREM"; }
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
            if (val.getType() != Value.Type.ZSET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            RZSet zset = val.asZSet();
            int removed = 0;
            for (int i = 2; i < args.size(); i++) {
                ByteKey member = ByteKey.of(args.get(i));
                if (zset.remove(member)) {
                    removed++;
                }
            }
            if (zset.isEmpty()) {
                db.del(key);
            } else {
                val.touch();
                db.touchKey(key);
            }
            if (removed > 0) recordMutation(sc, ctx.dbIndex, args);
            out.integer(removed);
        }
    }

    public static final class ZScoreCmd implements Command {
        private final ServerContext sc;
        public ZScoreCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZSCORE"; }
        @Override public int arity() { return 3; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            ByteKey member = ByteKey.of(args.get(2));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.nullBulk();
                return;
            }
            if (val.getType() != Value.Type.ZSET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }
            Double score = val.asZSet().getScore(member);
            if (score == null) {
                out.nullBulk();
            } else {
                out.bulk(formatDouble(score));
            }
        }
    }

    public static final class ZRankCmd implements Command {
        private final ServerContext sc;
        public ZRankCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZRANK"; }
        @Override public int arity() { return 3; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            ByteKey member = ByteKey.of(args.get(2));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.nullBulk();
                return;
            }
            if (val.getType() != Value.Type.ZSET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }
            Long rank = val.asZSet().getRank(member, false);
            if (rank == null) {
                out.nullBulk();
            } else {
                out.integer(rank);
            }
        }
    }

    public static final class ZRevRankCmd implements Command {
        private final ServerContext sc;
        public ZRevRankCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZREVRANK"; }
        @Override public int arity() { return 3; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            ByteKey member = ByteKey.of(args.get(2));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.nullBulk();
                return;
            }
            if (val.getType() != Value.Type.ZSET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }
            Long rank = val.asZSet().getRank(member, true);
            if (rank == null) {
                out.nullBulk();
            } else {
                out.integer(rank);
            }
        }
    }

    public static final class ZRangeCmd implements Command {
        private final ServerContext sc;
        public ZRangeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZRANGE"; }
        @Override public int arity() { return -4; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            handleRange(sc, ctx, args, out, false);
        }
    }

    public static final class ZRevRangeCmd implements Command {
        private final ServerContext sc;
        public ZRevRangeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZREVRANGE"; }
        @Override public int arity() { return -4; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            handleRange(sc, ctx, args, out, true);
        }
    }

    private static void handleRange(ServerContext sc, ClientContext ctx, List<byte[]> args, Reply out, boolean reverse) {
        ByteKey key = ByteKey.of(args.get(1));
        Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
        if (val == null) {
            out.emptyArray();
            return;
        }
        if (val.getType() != Value.Type.ZSET) {
            out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        try {
            long start = Long.parseLong(new String(args.get(2), StandardCharsets.US_ASCII));
            long stop = Long.parseLong(new String(args.get(3), StandardCharsets.US_ASCII));
            boolean withScores = false;
            if (args.size() > 4) {
                String opt = new String(args.get(4), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
                if (opt.equals("WITHSCORES")) {
                    withScores = true;
                }
            }

            List<RZSet.ScoredMember> list = val.asZSet().rangeByRank(start, stop, reverse);
            if (withScores) {
                out.arrayHeader(list.size() * 2);
                for (RZSet.ScoredMember sm : list) {
                    out.bulk(sm.member().getBytes());
                    out.bulk(formatDouble(sm.score()));
                }
            } else {
                out.arrayHeader(list.size());
                for (RZSet.ScoredMember sm : list) {
                    out.bulk(sm.member().getBytes());
                }
            }
        } catch (NumberFormatException e) {
            out.error("ERR value is not an integer or out of range");
        }
    }

    public static final class ZRangeByScoreCmd implements Command {
        private final ServerContext sc;
        public ZRangeByScoreCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZRANGEBYSCORE"; }
        @Override public int arity() { return -4; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.emptyArray();
                return;
            }
            if (val.getType() != Value.Type.ZSET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            try {
                double min = parseScoreBound(new String(args.get(2), StandardCharsets.US_ASCII));
                double max = parseScoreBound(new String(args.get(3), StandardCharsets.US_ASCII));
                boolean withScores = false;
                int offset = 0;
                int count = -1;

                for (int i = 4; i < args.size(); i++) {
                    String opt = new String(args.get(i), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
                    if (opt.equals("WITHSCORES")) {
                        withScores = true;
                    } else if (opt.equals("LIMIT") && i + 2 < args.size()) {
                        offset = Integer.parseInt(new String(args.get(++i), StandardCharsets.US_ASCII));
                        count = Integer.parseInt(new String(args.get(++i), StandardCharsets.US_ASCII));
                    }
                }

                List<RZSet.ScoredMember> list = val.asZSet().rangeByScore(min, max, offset, count);
                if (withScores) {
                    out.arrayHeader(list.size() * 2);
                    for (RZSet.ScoredMember sm : list) {
                        out.bulk(sm.member().getBytes());
                        out.bulk(formatDouble(sm.score()));
                    }
                } else {
                    out.arrayHeader(list.size());
                    for (RZSet.ScoredMember sm : list) {
                        out.bulk(sm.member().getBytes());
                    }
                }
            } catch (NumberFormatException e) {
                out.error("ERR min or max is not a float");
            }
        }
    }

    public static final class ZIncrByCmd implements Command {
        private final ServerContext sc;
        public ZIncrByCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZINCRBY"; }
        @Override public int arity() { return 4; }
        @Override public boolean isWrite() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            double delta;
            try {
                delta = Double.parseDouble(new String(args.get(2), StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                out.error("ERR value is not a valid float");
                return;
            }
            ByteKey member = ByteKey.of(args.get(3));

            RedisDb db = sc.getKeyspace().getDb(ctx.dbIndex);
            Value val = db.get(key);
            RZSet zset;
            if (val == null) {
                zset = new RZSet();
                val = new Value(Value.Type.ZSET, zset);
                db.put(key, val);
            } else {
                if (val.getType() != Value.Type.ZSET) {
                    out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                    return;
                }
                zset = val.asZSet();
            }

            Double cur = zset.getScore(member);
            double newScore = (cur == null ? 0 : cur) + delta;
            zset.add(newScore, member);
            val.touch();
            db.touchKey(key);
            recordMutation(sc, ctx.dbIndex, args);
            out.bulk(formatDouble(newScore));
        }
    }

    public static final class ZCardCmd implements Command {
        private final ServerContext sc;
        public ZCardCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZCARD"; }
        @Override public int arity() { return 2; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.integer(0);
            } else if (val.getType() != Value.Type.ZSET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            } else {
                out.integer(val.asZSet().size());
            }
        }
    }

    public static final class ZCountCmd implements Command {
        private final ServerContext sc;
        public ZCountCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "ZCOUNT"; }
        @Override public int arity() { return 4; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ByteKey key = ByteKey.of(args.get(1));
            Value val = sc.getKeyspace().getDb(ctx.dbIndex).get(key);
            if (val == null) {
                out.integer(0);
                return;
            }
            if (val.getType() != Value.Type.ZSET) {
                out.error("WRONGTYPE Operation against a key holding the wrong kind of value");
                return;
            }

            try {
                double min = parseScoreBound(new String(args.get(2), StandardCharsets.US_ASCII));
                double max = parseScoreBound(new String(args.get(3), StandardCharsets.US_ASCII));
                out.integer(val.asZSet().count(min, max));
            } catch (NumberFormatException e) {
                out.error("ERR min or max is not a float");
            }
        }
    }

    private static double parseScoreBound(String s) {
        if (s.equals("-inf")) return Double.NEGATIVE_INFINITY;
        if (s.equals("+inf") || s.equals("inf")) return Double.POSITIVE_INFINITY;
        return Double.parseDouble(s);
    }

    private static String formatDouble(double d) {
        if (d == (long) d) {
            return String.format(Locale.ROOT, "%d", (long) d);
        } else {
            return String.format(Locale.ROOT, "%s", d);
        }
    }
}
