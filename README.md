# JRedis: Redis-Compatible In-Memory Key-Value Store in Java

[![Build](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Java](https://img.shields.io/badge/java-25-blue.svg)]()
[![License](https://img.shields.io/badge/license-MIT-green.svg)]()

A fast, plain, Redis-compatible in-memory database built from scratch in pure Java (Java 25) with raw `java.nio`.

---

## 1. Architecture

JRedis executes all client commands on a single-threaded non-blocking NIO event loop, ensuring **atomic command execution without locks**.

```
clients (redis-cli, redis-benchmark, apps)
        │  TCP / RESP2 (port 6379 / custom)
┌───────▼────────────────────────────────────────────────┐
│ EventLoop (java.nio.channels.Selector)                 │
│ Single-threaded non-blocking accept, read, dispatch,   │
│ write, and 100ms serverCron ticks                      │
├────────────────────────────────────────────────────────┤
│ Connection & ClientContext                             │
│ - Partial read buffer (ByteBuffer accumulation)        │
│ - Output write queue (non-blocking OP_WRITE handling)   │
│ - Selected DB index, Txn state, PubSub subscriptions   │
├────────────────────────────────────────────────────────┤
│ RespParser & RespWriter (Reply)                        │
│ - Incremental RESP2 parser (supports chunked buffers)  │
│ - Inline command parsing fallback (telnet style)       │
│ - Binary-safe serialization for Simple, Error, Int,    │
│   Bulk, and Arrays                                     │
├────────────────────────────────────────────────────────┤
│ Command Registry & Dispatcher                          │
│ - Fast case-insensitive command lookup                 │
│ - Arity checking, flag verification, SlowLog timing    │
├────────────────────────────────────────────────────────┤
│ Keyspace & Multi-DB Store                              │
│ - ByteKey wrapper (pre-hashed, constant-time equals)   │
│ - Value container (Type, Data, LRU/LFU metadata)       │
│ - Dict: standard Map / IncrementalDict (v0.9)          │
│ - ExpiryManager: lazy expiry + 10Hz active sampling    │
├───────────────┬────────────────────────┬───────────────┤
│ Data Types    │ Persistence            │ Memory & Obs  │
│ - Strings     │ - AOF (fsync policies, │ - maxmemory   │
│ - Lists       │   rewrite, crash-safe) │ - LRU / LFU   │
│ - Hashes      │ - RDB (binary format,  │ - Random      │
│ - Sets        │   CRC32 checksum)      │ - SLOWLOG     │
│ - SkipList ZSet│                        │ - INFO stats  │
└───────────────┴────────────────────────┴───────────────┘
```

---

## 2. Supported Commands

JRedis implements over 50 Redis commands across all data types, transactions, pub/sub, and persistence:

| Category | Commands |
|---|---|
| **Base & Connection** | `PING`, `ECHO`, `QUIT`, `COMMAND`, `INFO`, `CLIENT (SETNAME, GETNAME, ID, LIST, INFO)` |
| **Strings & Cache** | `SET` (EX, PX, NX, XX, GET), `GET`, `DEL`, `EXISTS`, `TYPE`, `INCR`, `DECR`, `INCRBY`, `DECRBY`, `APPEND`, `STRLEN`, `GETRANGE`, `SETRANGE`, `MGET`, `MSET` |
| **Expiry & Keyspace** | `EXPIRE`, `PEXPIRE`, `EXPIREAT`, `PEXPIREAT`, `TTL`, `PTTL`, `PERSIST`, `KEYS`, `SCAN`, `DBSIZE`, `SELECT`, `FLUSHDB`, `FLUSHALL`, `TIME` |
| **Lists** | `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE`, `LINDEX`, `LSET`, `LREM`, `LTRIM` |
| **Hashes** | `HSET`, `HGET`, `HDEL`, `HGETALL`, `HLEN`, `HEXISTS`, `HINCRBY`, `HMGET`, `HMSET`, `HKEYS`, `HVALS` |
| **Sets** | `SADD`, `SREM`, `SISMEMBER`, `SMEMBERS`, `SCARD`, `SINTER`, `SUNION`, `SDIFF` |
| **Sorted Sets (ZSet)** | `ZADD`, `ZREM`, `ZSCORE`, `ZRANK`, `ZREVRANK`, `ZRANGE`, `ZREVRANGE`, `ZRANGEBYSCORE`, `ZINCRBY`, `ZCARD`, `ZCOUNT` |
| **Transactions** | `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH` |
| **Pub/Sub** | `SUBSCRIBE`, `UNSUBSCRIBE`, `PUBLISH` |
| **Persistence** | `SAVE`, `BGSAVE`, `BGREWRITEAOF` |
| **Configuration & Memory** | `CONFIG GET`, `CONFIG SET`, `MEMORY USAGE`, `SLOWLOG (GET, LEN, RESET)` |

---

## 3. Key Design Decisions

### Binary-Safe Keys & Values (`ByteKey`)
Keys and string values are raw `byte[]`, never converted to `String` in the storage hot-path. The `ByteKey` class caches `hashCode()` upon creation and implements lexicographical comparison for consistent ordering.

### Custom Skip List with Rank Spans (`SkipList`)
The sorted set implementation uses a William Pugh probabilistic skip list with maximum level 32 and forward level spans (`span`). This enables $O(\log N)$ rank lookups (`ZRANK`, `ZREVRANK`) and element-by-rank queries (`ZRANGE`) alongside a dictionary for $O(1)$ score lookups (`ZSCORE`).

### Incremental Dual-Table Rehashing (`IncrementalDict`)
To eliminate latency spikes when growing to millions of keys, JRedis provides an incremental hash table featuring two internal tables (`ht[0]` and `ht[1]`). When resizing, buckets are migrated incrementally during regular reads and writes, as well as during periodic event-loop ticks.

### Persistence: Deterministic AOF & Binary RDB Snapshots
- **AOF (Append-Only File):** Mutating commands are logged in canonical RESP format. Relative expirations (`EXPIRE`) are deterministically rewritten into absolute millisecond timestamps (`PEXPIREAT`) so replay is completely deterministic. Supports `always`, `everysec`, and `no` fsync policies. Trailing truncated commands from process crashes are automatically tolerated and recovered.
- **RDB (Binary Snapshots):** Custom binary format (`JREDIS01`) with opcode headers and CRC32 checksum. Supports synchronous `SAVE` and background asynchronous `BGSAVE` snapshotting.

### Memory Boundedness & Approximated Eviction
Configurable `maxmemory` enforces memory budgets. Eviction policies include:
- `noeviction` (returns OOM error)
- `allkeys-lru` / `volatile-lru` (sampled LRU pool)
- `allkeys-lfu` / `volatile-lfu` (Morris 8-bit probabilistic logarithmic counter with decay)
- `allkeys-random` / `volatile-random`

---

## 4. How to Add a New Command

Adding a new command takes 3 simple steps:

1. Create a class implementing `org.example.command.Command`:
   ```java
   public final class MyCommand implements Command {
       @Override public String name() { return "MYCMD"; }
       @Override public int arity() { return 2; } // 1 for name + 1 arg
       @Override public boolean isReadOnly() { return true; }
       @Override
       public void execute(ClientContext ctx, List<byte[]> args, Reply out) {
           out.bulk("hello from mycmd");
       }
   }
   ```
2. Register the command in `Server.java`:
   ```java
   registry.register(new MyCommand());
   ```
3. Add a unit test verifying expected RESP serialization.

---

## 5. Building and Running

### Requirements
- JDK 25 (or compatible JDK 21+ toolchain)
- Gradle wrapper (included)

### Build and Test
```bash
# Run unit and integration tests
./gradlew test

# Build executable distribution
./gradlew build
```

### Run the Server
```bash
./gradlew run --args="--port 6379 --bind 127.0.0.1 --appendonly yes"
```

### Connect with `redis-cli`
```bash
redis-cli -p 6379 ping
# PONG

redis-cli -p 6379 set greeting "Hello JRedis"
# OK

redis-cli -p 6379 get greeting
# "Hello JRedis"
```
