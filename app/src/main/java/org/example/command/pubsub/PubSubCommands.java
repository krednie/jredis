package org.example.command.pubsub;

import org.example.command.Command;
import org.example.protocol.Reply;
import org.example.pubsub.PubSubManager;
import org.example.server.ClientContext;
import org.example.server.ServerContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class PubSubCommands {

    public static void registerAll(org.example.command.CommandRegistry reg, ServerContext serverContext) {
        reg.register(new SubscribeCmd(serverContext));
        reg.register(new UnsubscribeCmd(serverContext));
        reg.register(new PublishCmd(serverContext));
    }

    public static final class SubscribeCmd implements Command {
        private final ServerContext sc;
        public SubscribeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "SUBSCRIBE"; }
        @Override public int arity() { return -2; }
        @Override public boolean isPubSubAllowed() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            PubSubManager pm = sc.getPubSubManager();
            for (int i = 1; i < args.size(); i++) {
                String ch = new String(args.get(i), StandardCharsets.UTF_8);
                int count = pm.subscribe(ctx.connection, ch);
                out.arrayHeader(3);
                out.bulk("subscribe");
                out.bulk(ch);
                out.integer(count);
            }
        }
    }

    public static final class UnsubscribeCmd implements Command {
        private final ServerContext sc;
        public UnsubscribeCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "UNSUBSCRIBE"; }
        @Override public int arity() { return -1; }
        @Override public boolean isPubSubAllowed() { return true; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            PubSubManager pm = sc.getPubSubManager();
            if (args.size() == 1) {
                // Unsubscribe from all
                for (String ch : List.copyOf(ctx.subscribedChannels)) {
                    int count = pm.unsubscribe(ctx.connection, ch);
                    out.arrayHeader(3);
                    out.bulk("unsubscribe");
                    out.bulk(ch);
                    out.integer(count);
                }
            } else {
                for (int i = 1; i < args.size(); i++) {
                    String ch = new String(args.get(i), StandardCharsets.UTF_8);
                    int count = pm.unsubscribe(ctx.connection, ch);
                    out.arrayHeader(3);
                    out.bulk("unsubscribe");
                    out.bulk(ch);
                    out.integer(count);
                }
            }
        }
    }

    public static final class PublishCmd implements Command {
        private final ServerContext sc;
        public PublishCmd(ServerContext sc) { this.sc = sc; }
        @Override public String name() { return "PUBLISH"; }
        @Override public int arity() { return 3; }

        @Override
        public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
            String ch = new String(args.get(1), StandardCharsets.UTF_8);
            byte[] msg = args.get(2);
            int count = sc.getPubSubManager().publish(ch, msg);
            out.integer(count);
        }
    }
}
