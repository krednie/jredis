package org.example;

import org.example.persist.AofWriter;
import org.example.server.Server;

public class App {
    public String getGreeting() {
        return "JRedis server running";
    }

    public static void main(String[] args) {
        int port = 6379;
        String bind = "127.0.0.1";
        long maxMemory = 0;
        String policy = "noeviction";
        boolean aof = false;
        String dir = ".";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> {
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                }
                case "--bind", "-b" -> {
                    if (i + 1 < args.length) bind = args[++i];
                }
                case "--maxmemory" -> {
                    if (i + 1 < args.length) maxMemory = Long.parseLong(args[++i]);
                }
                case "--maxmemory-policy" -> {
                    if (i + 1 < args.length) policy = args[++i];
                }
                case "--appendonly" -> {
                    if (i + 1 < args.length) aof = "yes".equalsIgnoreCase(args[++i]);
                }
                case "--dir" -> {
                    if (i + 1 < args.length) dir = args[++i];
                }
            }
        }

        System.out.println("""
                                       
     ███                         █████  ███         
     ▒▒▒                         ▒▒███  ▒▒▒          
     █████ ████████   ██████   ███████  ████   █████ 
    ▒▒███ ▒▒███▒▒███ ███▒▒███ ███▒▒███ ▒▒███  ███▒▒  
     ▒███  ▒███ ▒▒▒ ▒███████ ▒███ ▒███  ▒███ ▒▒█████ 
     ▒███  ▒███     ▒███▒▒▒  ▒███ ▒███  ▒███  ▒▒▒▒███
     ▒███  █████    ▒▒██████ ▒▒████████ █████ ██████ 
     ▒███ ▒▒▒▒▒      ▒▒▒▒▒▒   ▒▒▒▒▒▒▒▒ ▒▒▒▒▒ ▒▒▒▒▒▒  
 ███ ▒███                                            
▒▒██████       ▌    ▌      ▌  ▘  
 ▒▒▒▒▒▒  ▌▌▀▌▛▘▛▌   ▙▘▛▘█▌▛▌▛▌▌█▌
         ▙▌█▌▄▌▌▌  @▛▖▌ ▙▖▙▌▌▌▌▙▖ 2502052507
         ▄▌      
                    
             
 
            """);
        System.out.println("Starting JRedis on " + bind + ":" + port + "...");

        Server.Config config = new Server.Config(
                port,
                bind,
                16,
                maxMemory,
                policy,
                aof,
                AofWriter.FsyncPolicy.EVERYSEC,
                "appendonly.aof",
                true,
                "dump.rdb",
                dir
        );

        Server server = new Server(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping JRedis server...");
            server.close();
            System.out.println("JRedis stopped.");
        }));

        try {
            server.start();
            System.out.println("JRedis ready to accept connections on port " + port);
            // Keep main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            System.exit(1);
        }
    }
}
