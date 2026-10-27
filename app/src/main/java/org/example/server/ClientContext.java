package org.example.server;

import org.example.command.Command;
import org.example.protocol.Reply;
import org.example.store.ByteKey;

import java.nio.channels.SocketChannel;
import java.util.*;

public final class ClientContext {
    private static long nextId = 1;

    public final long id = nextId++;
    public int dbIndex = 0;
    public String name = "";
    public SocketChannel channel;
    public Connection connection;

    public boolean closeRequested = false;
    public boolean pubSubMode = false;
    public final Set<String> subscribedChannels = new HashSet<>();
    public final Set<String> subscribedPatterns = new HashSet<>();

    // Transaction state
    public boolean inTransaction = false;
    public boolean transactionAborted = false;
    public final List<QueuedCommand> queuedCommands = new ArrayList<>();
    public final Map<Integer, Set<ByteKey>> watchedKeys = new HashMap<>();

    public final long connectedAtMs = System.currentTimeMillis();
    public long lastActivityMs = System.currentTimeMillis();

    public record QueuedCommand(Command command, List<byte[]> args) {}

    public void touch() {
        this.lastActivityMs = System.currentTimeMillis();
    }

    public void resetTransaction() {
        inTransaction = false;
        transactionAborted = false;
        queuedCommands.clear();
        watchedKeys.clear();
    }

    public void watchKey(int db, ByteKey key) {
        watchedKeys.computeIfAbsent(db, k -> new HashSet<>()).add(key);
    }

    public void unwatchAll() {
        watchedKeys.clear();
    }
}