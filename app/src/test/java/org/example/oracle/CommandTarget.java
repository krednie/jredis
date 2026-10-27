package org.example.oracle;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

public interface CommandTarget extends AutoCloseable {

    default void close() throws Exception {
    };

    byte[] send(List<byte[]> args) throws Exception;

    default byte[] send(String... args) throws Exception {

        List<byte[]> raw = new ArrayList<>(args.length);
        for (String s : args) {
            raw.add(s.getBytes(StandardCharsets.UTF_8));
        }
        return send(raw);
    }
}

/*
 * why empty
 * parameter and
 * body in close?
 */