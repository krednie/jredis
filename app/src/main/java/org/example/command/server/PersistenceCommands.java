package org.example.command.server;

import org.example.command.Command;
import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;

import java.io.IOException;
import java.util.List;

public final class PersistenceCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new SaveCmd(serverContext));
        reg.register(new BgSaveCmd(serverContext));
        reg.register(new BgRewriteAofCmd(serverContext));
    }

    public static final class SaveCmd implements Command {
        private final ServerContext sc;
        public SaveCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SAVE"; }
        @Override public int arity() { return 1; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if (sc.getRdbSnapshotter() == null) {
                out.error("ERR persistence not configured");
                return;
            }
            try {
                sc.getRdbSnapshotter().save();
                out.simple("OK");
            } catch (IOException e) {
                out.error("ERR save failed: " + e.getMessage());
            }
        }
    }

    public static final class BgSaveCmd implements Command {
        private final ServerContext sc;
        public BgSaveCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "BGSAVE"; }
        @Override public int arity() { return 1; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if (sc.getRdbSnapshotter() == null) {
                out.error("ERR persistence not configured");
                return;
            }
            if (sc.getRdbSnapshotter().bgsave()) {
                out.simple("Background saving started");
            } else {
                out.error("ERR background save already in progress");
            }
        }
    }

    public static final class BgRewriteAofCmd implements Command {
        private final ServerContext sc;
        public BgRewriteAofCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "BGREWRITEAOF"; }
        @Override public int arity() { return 1; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            if (sc.getAofWriter() == null) {
                out.error("ERR AOF not enabled");
                return;
            }
            try {
                sc.getAofWriter().rewrite(sc.getKeyspace());
                out.simple("Background append only file rewriting started");
            } catch (IOException e) {
                out.error("ERR rewrite failed: " + e.getMessage());
            }
        }
    }
}
