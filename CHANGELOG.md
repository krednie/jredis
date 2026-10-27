# Changelog

All notable changes to the JRedis project across each version milestone.

## [v1.0] - Stable Base
### Added
- Complete CLI entrypoint `App`  supporting `--port`, `--bind`, `--maxmemory`, `--maxmemory-policy`, `--appendonly`, `--dir`.
- Comprehensive architecture and extension documentation in `README.md`.
- End-to-end integration test suites across all data types, persistence, and networking.
- Frozen extension points: `Command`, `EvictionPolicy`, `ByteKey`, `Value` per-entry metadata.

## [v0.9] - Internals: Incremental Rehash
### Added
- `IncrementalDict` with dual-table design (`ht[0]`, `ht[1]`).
- Step-by-step incremental rehashing per read/write and during event loop ticks to prevent latency spikes during growth.

## [v0.8] - Pub/Sub, Transactions, Observability
### Added
- Transaction management: `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`.
- Atomic multi-command execution and CAS optimistic locking with dirty key invalidation.
- Pub/Sub subsystem: `SUBSCRIBE`, `UNSUBSCRIBE`, `PUBLISH` supporting direct channels and pattern subscriptions.
- Observability: `SLOWLOG` circular ring buffer (`GET`, `LEN`, `RESET`) and execution latency tracking.

## [v0.7] - Bounded Memory & Eviction
### Added
- JVM per-entry memory tracking and size estimation.
- Configurable `maxmemory` limit enforcement.
- Eviction policies: `noeviction`, `allkeys-lru`, `volatile-lru`, `allkeys-lfu`, `volatile-lfu`, `allkeys-random`, `volatile-random`.
- Morris 8-bit probabilistic logarithmic counter with decay for LFU and sampled LRU pool.

## [v0.6] - Snapshots (RDB)
### Added
- Binary RDB format (`JREDIS01`) with opcode headers and CRC32 checksum.
- Synchronous `SAVE` command.
- Asynchronous background snapshotting `BGSAVE` with auto-save intervals.
- Full RDB loader with checksum validation.

## [v0.5] - Survives Restarts (AOF)
### Added
- Append-Only File (`AofWriter`) logging mutating commands in canonical RESP format.
- Deterministic relative-to-absolute expiration rewriting (`PEXPIREAT`).
- Configurable fsync policies: `always`, `everysec`, `no`.
- Crash-safe `AofLoader` tolerant of truncated trailing commands.
- `AofRewriter` (`BGREWRITEAOF`) compacting keyspace to minimal commands.

## [v0.4] - Sorted Sets
### Added
- Custom `SkipList` with level rank spans for $O(\log N)$ rank computation.
- `RZSet` pairing SkipList with hash table for $O(1)$ score lookups.
- Sorted set commands: `ZADD`, `ZREM`, `ZSCORE`, `ZRANK`, `ZREVRANK`, `ZRANGE`, `ZREVRANGE`, `ZRANGEBYSCORE`, `ZINCRBY`, `ZCARD`, `ZCOUNT`.

## [v0.3] - Lists, Hashes, Sets
### Added
- List commands: `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE`, `LINDEX`, `LSET`, `LREM`, `LTRIM`.
- Hash commands: `HSET`, `HGET`, `HDEL`, `HGETALL`, `HLEN`, `HEXISTS`, `HINCRBY`, `HMGET`, `HMSET`, `HKEYS`, `HVALS`.
- Set commands: `SADD`, `SREM`, `SISMEMBER`, `SMEMBERS`, `SCARD`, `SINTER`, `SUNION`, `SDIFF`.
- Strict type checking with `-WRONGTYPE` errors.

## [v0.2] - It's a Cache
### Added
- Binary-safe `ByteKey` wrapper with cached hash.
- Multi-database keyspace (DB 0..15).
- String commands: `SET` (EX, PX, NX, XX, GET), `GET`, `DEL`, `EXISTS`, `TYPE`, `INCR`, `DECR`, `INCRBY`, `DECRBY`, `APPEND`, `STRLEN`, `GETRANGE`, `SETRANGE`, `MGET`, `MSET`.
- Expiry commands: `EXPIRE`, `PEXPIRE`, `EXPIREAT`, `PEXPIREAT`, `TTL`, `PTTL`, `PERSIST`.
- Probabilistic active expiry cycle in 10Hz `serverCron`.
- Database commands: `KEYS`, `SCAN`, `FLUSHALL`, `FLUSHDB`, `DBSIZE`, `SELECT`.

## [v0.1] - Talks RESP
### Added
- Single-threaded non-blocking `java.nio` `EventLoop` with Selector.
- Incremental `RespParser` supporting chunks, pipelining, and telnet inline commands.
- `Reply` binary-safe serializer for all RESP2 types.
- Base commands: `PING`, `ECHO`, `QUIT`, `COMMAND`, `INFO`, `CLIENT`.
