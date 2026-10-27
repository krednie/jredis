package org.example.command.txn;

import org.example.command.Command;
import org.example.command.CommandRegistry;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.ByteKey;

import java.util.ArrayList;
import java.util.List;

public final class TransactionCommands {

    public static void registerAll(CommandRegistry reg, ServerContext serverContext) {
        reg.register(new MultiCmd());
        reg.register(new ExecCmd(reg));
        reg.register(new DiscardCmd());
        reg.register(new WatchCmd(serverContext));
        reg.register(new UnwatchCmd());
    }

    public static final class MultiCmd implements Command {
        @Override public String name() { return "MULTI"; }
        @Override public int arity() { return 1; }
        @Override public boolean isNoMulti() { return true; }
        @Override public boolean isPubSubAllowed() { return false; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if (ctx.inTransaction) {
                out.error("ERR MULTI calls can not be nested");
                return;
            }
            ctx.inTransaction = true;
            ctx.transactionAborted = false;
            ctx.queuedCommands.clear();
            out.simple("OK");
        }
    }

    public static final class DiscardCmd implements Command {
        @Override public String name() { return "DISCARD"; }
        @Override public int arity() { return 1; }
        @Override public boolean isNoMulti() { return true; }
        @Override public boolean isPubSubAllowed() { return false; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if (!ctx.inTransaction) {
                out.error("ERR DISCARD without MULTI");
                return;
            }
            ctx.resetTransaction();
            out.simple("OK");
        }
    }

    public static final class WatchCmd implements Command {
        private final ServerContext sc;
        public WatchCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "WATCH"; }
        @Override public int arity() { return -2; }
        @Override public boolean isNoMulti() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if (ctx.inTransaction) {
                out.error("ERR WATCH inside MULTI is not allowed");
                return;
            }
            for (int i = 1; i < args.size(); i++) {
                ByteKey key = ByteKey.of(args.get(i));
                sc.getKeyspace().getDb(ctx.dbIndex).watch(ctx, key);
            }
            out.simple("OK");
        }
    }

    public static final class UnwatchCmd implements Command {
        @Override public String name() { return "UNWATCH"; }
        @Override public int arity() { return 1; }
        @Override public boolean isNoMulti() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            ctx.unwatchAll();
            out.simple("OK");
        }
    }

    public static final class ExecCmd implements Command {
        private final CommandRegistry registry;
        public ExecCmd(CommandRegistry registry) { this.registry = registry; }
        @Override public String name() { return "EXEC"; }
        @Override public int arity() { return 1; }
        @Override public boolean isNoMulti() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if (!ctx.inTransaction) {
                out.error("ERR EXEC without MULTI");
                return;
            }

            if (ctx.transactionAborted) {
                ctx.resetTransaction();
                out.nullArray();
                return;
            }

            List<ClientContext.QueuedCommand> queue = new ArrayList<>(ctx.queuedCommands);
            ctx.resetTransaction();

            out.arrayHeader(queue.size());
            for (ClientContext.QueuedCommand q : queue) {
                Reply cmdReply = new Reply();
                q.command().execute(ctx, q.args(), cmdReply);
                out.raw(cmdReply.toBytes());
            }
        }
    }
}
