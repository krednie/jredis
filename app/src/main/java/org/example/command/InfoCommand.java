package org.example.command;

import org.example.protocol.Reply;
import org.example.server.ClientContext;
import org.example.server.ServerContext;
import org.example.store.RedisDb;

import java.util.List;

public final class InfoCommand implements Command {
    private final ServerContext serverContext;

    public InfoCommand(ServerContext serverContext) {
        this.serverContext = serverContext;
    }

    @Override
    public String name() {
        return "INFO";
    }

    @Override
    public int arity() {
        return -1;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
        StringBuilder sb = new StringBuilder();
        long uptime = (System.currentTimeMillis() - serverContext.startTimeMs) / 1000;

        sb.append("# Server\r\n");
        sb.append("redis_version:").append(ServerContext.VERSION).append("\r\n");
        sb.append("tcp_port:").append(serverContext.getPort()).append("\r\n");
        sb.append("uptime_in_seconds:").append(uptime).append("\r\n");

        sb.append("# Clients\r\n");
        sb.append("connected_clients:").append(serverContext.getClientCount()).append("\r\n");

        sb.append("# Memory\r\n");
        long usedMem = serverContext.getMemoryTracker() != null ? serverContext.getMemoryTracker().getUsedMemory() : 0;
        long maxMem = serverContext.getMemoryTracker() != null ? serverContext.getMemoryTracker().getMaxMemory() : 0;
        sb.append("used_memory:").append(usedMem).append("\r\n");
        sb.append("used_memory_human:").append(usedMem / 1024).append("K\r\n");
        sb.append("maxmemory:").append(maxMem).append("\r\n");

        sb.append("# Persistence\r\n");
        sb.append("aof_enabled:").append(serverContext.getAofWriter() != null ? 1 : 0).append("\r\n");
        sb.append("rdb_bgsave_in_progress:").append(
                serverContext.getRdbSnapshotter() != null && serverContext.getRdbSnapshotter().isBgsaveInProgress() ? 1 : 0
        ).append("\r\n");

        sb.append("# Stats\r\n");
        sb.append("total_commands_processed:").append(serverContext.getRegistry().getTotalCommandsProcessed()).append("\r\n");

        sb.append("# Keyspace\r\n");
        if (serverContext.getKeyspace() != null) {
            for (RedisDb db : serverContext.getKeyspace().getAllDbs()) {
                int keys = db.size();
                int expires = db.getExpiringKeys().size();
                if (keys > 0 || expires > 0) {
                    sb.append("db").append(db.getId()).append(":keys=").append(keys).append(",expires=").append(expires).append("\r\n");
                }
            }
        }

        out.bulk(sb.toString());
    }
}
