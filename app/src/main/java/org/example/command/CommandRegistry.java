package org.example.command;

import org.example.obs.SlowLog;
import org.example.protocol.Reply;
import org.example.server.ClientContext;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class CommandRegistry {
    private final Map<String, Command> commands = new HashMap<>();
    private final AtomicLong totalCommandsProcessed = new AtomicLong();
    private SlowLog slowLog;

    public void setSlowLog(SlowLog slowLog) {
        this.slowLog = slowLog;
    }

    public void register(Command c) {
        commands.put(c.name().toUpperCase(Locale.ROOT), c);
    }

    public Command get(String name) {
        return commands.get(name.toUpperCase(Locale.ROOT));
    }

    public Collection<Command> getAll() {
        return Collections.unmodifiableCollection(commands.values());
    }

    public long getTotalCommandsProcessed() {
        return totalCommandsProcessed.get();
    }

    public void dispatch(ClientContext ctx, List<byte[]> args, Reply out) {
        if (args == null || args.isEmpty()) {
            return;
        }

        byte[] rawNameBytes = args.get(0);
        if (rawNameBytes == null || rawNameBytes.length == 0) {
            return;
        }

        String raw = new String(rawNameBytes, StandardCharsets.UTF_8);
        String nameUpper = raw.toUpperCase(Locale.ROOT);
        Command cmd = commands.get(nameUpper);

        if (cmd == null) {
            out.error("ERR unknown command '" + raw + "'");
            if (ctx.inTransaction) {
                ctx.transactionAborted = true;
            }
            return;
        }

        int n = args.size();
        int arity = cmd.arity();
        if ((arity > 0 && n != arity) || (arity < 0 && n < -arity)) {
            out.error("ERR wrong number of arguments for '" + cmd.name().toLowerCase(Locale.ROOT) + "' command");
            if (ctx.inTransaction) {
                ctx.transactionAborted = true;
            }
            return;
        }

        // PubSub context check
        if (ctx.pubSubMode && !cmd.isPubSubAllowed()) {
            out.error("ERR only (P)SUBSCRIBE / (P)UNSUBSCRIBE / PING / QUIT allowed in this context");
            return;
        }

        // Transaction MULTI queuing check
        if (ctx.inTransaction && !cmd.isNoMulti()) {
            ctx.queuedCommands.add(new ClientContext.QueuedCommand(cmd, args));
            out.simple("QUEUED");
            return;
        }

        // Execute command
        long startNanos = System.nanoTime();
        try {
            cmd.execute(ctx, args, out);
            totalCommandsProcessed.incrementAndGet();
        } catch (Exception e) {
            out.error("ERR internal server error: " + e.getMessage());
        } finally {
            long durationMicros = (System.nanoTime() - startNanos) / 1000;
            if (slowLog != null) {
                slowLog.recordIfSlow(args, durationMicros);
            }
        }
    }
}
