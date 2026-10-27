package org.example.server;

import org.example.command.CommandRegistry;
import org.example.protocol.Reply;
import org.example.protocol.RespParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public final class Connection {
    public static final int BUFFER_SIZE = 64 * 1024; // 64KB initial
    public static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024; // 64MB limit

    private final SocketChannel channel;
    private final ClientContext ctx;
    private final CommandRegistry registry;
    private ByteBuffer readBuf = ByteBuffer.allocate(BUFFER_SIZE);
    private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();
    private long totalQueuedBytes = 0;
    private boolean closed = false;

    public Connection(SocketChannel channel, CommandRegistry registry) {
        this.channel = channel;
        this.registry = registry;
        this.ctx = new ClientContext();
        this.ctx.channel = channel;
        this.ctx.connection = this;
    }

    public ClientContext getContext() {
        return ctx;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (!closed) {
            closed = true;
            try {
                channel.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Reads data from channel, parses RESP commands, and dispatches them.
     * @return false if the connection was closed by the client.
     */
    public boolean handleRead(SelectionKey key) throws IOException {
        ctx.touch();
        int bytesRead;
        try {
            bytesRead = channel.read(readBuf);
        } catch (IOException e) {
            close();
            return false;
        }

        if (bytesRead == -1) {
            close();
            return false;
        }
        if (bytesRead == 0) {
            return true;
        }

        readBuf.flip();

        while (readBuf.hasRemaining()) {
            List<byte[]> args;
            try {
                args = RespParser.parse(readBuf);
            } catch (Exception e) {
                Reply err = new Reply();
                err.error("ERR Protocol error: " + e.getMessage());
                queueReply(err.toBytes(), key);
                close();
                return false;
            }

            if (args == null) {
                // Incomplete frame; need more bytes
                break;
            }

            if (!args.isEmpty()) {
                Reply out = new Reply();
                registry.dispatch(ctx, args, out);
                if (!out.isEmpty()) {
                    queueReply(out.toBytes(), key);
                }
                if (ctx.closeRequested) {
                    flush(key);
                    close();
                    return false;
                }
            }
        }

        // Compact buffer to keep unconsumed bytes at the beginning
        if (readBuf.position() > 0) {
            readBuf.compact();
        } else if (!readBuf.hasRemaining()) {
            // If full and no progress was made, resize if below max
            if (readBuf.capacity() < MAX_BUFFER_SIZE) {
                ByteBuffer newBuf = ByteBuffer.allocate(readBuf.capacity() * 2);
                readBuf.flip();
                newBuf.put(readBuf);
                readBuf = newBuf;
            } else {
                Reply err = new Reply();
                err.error("ERR query buffer length exceeds maximum");
                queueReply(err.toBytes(), key);
                close();
                return false;
            }
        }

        return true;
    }

    public void queueReply(byte[] bytes, SelectionKey key) {
        if (bytes == null || bytes.length == 0 || closed) {
            return;
        }
        if (totalQueuedBytes + bytes.length > MAX_BUFFER_SIZE) {
            close();
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        writeQueue.add(buf);
        totalQueuedBytes += bytes.length;

        // Try immediate flush
        try {
            flush(key);
        } catch (IOException e) {
            close();
        }
    }

    public void flush(SelectionKey key) throws IOException {
        while (!writeQueue.isEmpty()) {
            ByteBuffer head = writeQueue.peek();
            channel.write(head);
            if (head.hasRemaining()) {
                // Socket buffer full; register OP_WRITE to continue later
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
                return;
            }
            totalQueuedBytes -= head.capacity();
            writeQueue.poll();
        }

        // All pending writes finished; turn off OP_WRITE
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }
}
