package org.example.persist;

import org.example.command.CommandRegistry;
import org.example.protocol.Reply;
import org.example.protocol.RespParser;
import org.example.server.ClientContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class AofLoader {

    public static int load(Path aofPath, CommandRegistry registry) throws IOException {
        if (!Files.exists(aofPath)) {
            return 0;
        }

        long fileSize = Files.size(aofPath);
        if (fileSize == 0) {
            return 0;
        }

        int commandCount = 0;
        ClientContext ctx = new ClientContext();

        try (FileChannel channel = FileChannel.open(aofPath, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(fileSize, 64 * 1024 * 1024));
            ByteBuffer accumulated = ByteBuffer.allocate(64 * 1024 * 1024);

            while (channel.read(buffer) > 0) {
                buffer.flip();
                accumulated.put(buffer);
                accumulated.flip();

                while (accumulated.hasRemaining()) {
                    List<byte[]> args;
                    try {
                        args = RespParser.parse(accumulated);
                    } catch (Exception e) {
                        // Truncated or corrupted trailing frame
                        System.err.println("AOF loader encountered trailing frame error: " + e.getMessage() + ", recovering partial log.");
                        accumulated.position(accumulated.limit());
                        break;
                    }

                    if (args == null) {
                        // Incomplete frame; need more bytes from file
                        break;
                    }

                    if (!args.isEmpty()) {
                        Reply reply = new Reply();
                        registry.dispatch(ctx, args, reply);
                        commandCount++;
                    }
                }

                accumulated.compact();
                buffer.clear();
            }
        }

        return commandCount;
    }
}
