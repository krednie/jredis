# Redis in Java, From Scratch: Versioned Implementation Plan

**Goal:** a plain, correct, fast Redis-compatible server in Java, built to learn the core concepts properly. The AI-caching twist comes *after* a stable base exists.
**Previous attempt:** [krednie/tinyredis](https://github.com/krednie/tinyredis) (C++17).
**Budget:** 4-5 h/day: ~2 h coding + 2-3 h learning.

**How this plan works:** every version is a **working, tagged release** with a theme, a scope, a demo and an acceptance test. You can stop after any version and still have something real. There is no big-bang "final product": v1.0 is just the version where the base is stable enough to build on.

---

## 1. Version roadmap at a glance

| Version | Theme | Days | Cumulative days | ≈ Weeks (7 d/wk) | You can now… |
|---|---|---|---|---|---|
| **v0.1** | Talks RESP | 12 | 12 | 1.7 | connect with `redis-cli`, `PING`/`ECHO` |
| **v0.2** | It's a cache | 7 | 19 | 2.7 | `SET/GET`, counters, TTLs, real clients work |
| **v0.3** | Lists, hashes, sets | 6 | 25 | 3.6 | store structured data |
| **v0.4** | Sorted sets | 7 | 32 | 4.6 | leaderboards, range queries (your own skip list) |
| **v0.5** | Survives restarts (AOF) | 9 | 41 | 5.9 | kill the server, restart, keep your data |
| **v0.6** | Snapshots (RDB) | 9 | 50 | 7.1 | fast restarts, compact backups |
| **v0.7** | Bounded memory | 7 | 57 | 8.1 | run with `maxmemory`, eviction policies |
| **v0.8** | Pub/Sub, transactions, observability | 8 | 65 | 9.3 | `MULTI/EXEC`, `PUBLISH`, `SLOWLOG` |
| **v0.9** | Internals: incremental rehash | 5 | 70 | 10 | no resize latency spikes |
| **v1.0** | Stable base | 5 | 75 | 10.7 | benchmarks, docs, frozen extension points |
| v1.1 *(optional)* | I/O threads | 6 | 81 | 11.6 | parallel socket I/O, single-threaded execution |
| v2.x | AI-caching twist | TBD | n/a | n/a | the research-driven part |

Total ≈ 150 coding hours up to v1.0. **Add 20-30% buffer for debugging**: network and persistence bugs are where schedules slip. Realistic range to v1.0: **10-14 weeks**. If you skip days, scale proportionally.

**Natural stop points**
- **v0.2:** first usable server
- **v0.4:** feature-rich in-memory server
- **v0.6:** durable server
- **v0.7:** complete general-purpose cache; the extension points for the AI twist exist by here
- **v1.0:** recommended branching point for v2.x

---

## 2. What to fix compared to tinyredis

| tinyredis (C++) | This time | Lands in |
|---|---|---|
| One thread per client, no mutex (README itself flags it) | Single-threaded NIO event loop; commands execute on one thread, so every command is atomic with no locks | v0.1 |
| Plain-text fallback mixed into the protocol path; no pipelining | Incremental RESP parser; inline commands as a separate small path; pipelining from day one | v0.1 |
| Expiry only on access | Lazy expiry **plus** an active expire cycle in a cron tick | v0.2 |
| Text AOF with relative `EXPIRE` | Deterministic AOF (absolute expiry timestamps), fsync policies, AOF rewrite | v0.5 |
| JSON snapshot | Binary RDB format | v0.6 |
| No eviction, no sorted sets | Approximated LRU/LFU, skip-list sorted sets | v0.4, v0.7 |
| Execution and persistence tangled in `db.cpp` | Layered: network → parser → dispatcher → commands → keyspace → persistence | v0.1 onward |

---

## 3. Architecture

```
clients (redis-cli, redis-benchmark, apps)
        │  TCP / RESP2
┌───────▼────────────────────────────┐
│ Event loop  (java.nio Selector)    │  one thread: accept, read, execute, write, cron
├────────────────────────────────────┤
│ Connection  (read buf, write queue)│  partial reads/writes, backpressure, limits
├────────────────────────────────────┤
│ RESP parser / writer               │  incremental, binary-safe
├────────────────────────────────────┤
│ Command registry + dispatcher      │  name, arity, flags, execute()
├────────────────────────────────────┤
│ Keyspace                           │  dict + expiry map + type-checked values
├──────────────┬─────────────────────┤
│ Data types   │ Persistence (AOF/RDB)│  eviction policy, memory accounting
└──────────────┴─────────────────────┘
```

**Decisions**
- Raw `java.nio`, no Netty (Netty hides exactly what you want to learn).
- Keys and values are `byte[]` (wrapped for hashing), never `String`. Binary-safe like real Redis.
- Latest LTS Java (21 or 25), Gradle, JUnit 5, JMH for microbenchmarks.
- Real Redis stays installed locally as the **oracle** for correctness and as the **baseline** for benchmarks.

**Package layout** (each package is introduced in the version shown)
```
src/main/java/.../
  server/    EventLoop, Connection, Server                        v0.1
  protocol/  RespParser, RespWriter, Reply                        v0.1
  command/   Command, CommandRegistry, StringCommands, ...        v0.1 → grows each version
  store/     Keyspace, Dict, ExpiryManager, ByteKey               v0.2 (Dict upgraded in v0.9)
  types/     RList, RHash, RSet            v0.3  |  RZSet, SkipList   v0.4
  persist/   AofWriter, AofLoader                                 v0.5
             RdbWriter, RdbLoader, Snapshotter                    v0.6
  evict/     EvictionPolicy, ApproxLru, Lfu, MemoryAccounting     v0.7
  pubsub/    PubSub, TxnState                                     v0.8
  obs/       SlowLog, LatencyHistogram                            v0.8
```

---

## 4. Versions in detail

### Release checklist (every version)
- [ ] All new commands diff-clean against real Redis (oracle harness)
- [ ] Unit + fuzz/property tests green
- [ ] `redis-benchmark` numbers recorded next to real Redis on the same machine
- [ ] `CHANGELOG.md` entry + `git tag vX.Y`
- [ ] README "supported commands" table updated

---

### v0.1: Talks RESP (12 days)
*Theme: a real Redis client can connect and get answers.*

**Scope**
- [ ] Gradle skeleton, `./gradlew test`
- [ ] `Command` interface + registry (name, arity, flags, execute)
- [ ] **Oracle test runner:** send the same command sequence to real Redis and your server, diff replies *(2 days)*
- [ ] **RESP2 parser/writer** *(4 days)*: incremental over `ByteBuffer` (returns "need more" on partial frames), all RESP types, pipelined commands in one buffer, inline commands for telnet as a separate path, size limits, fuzz test with random chunk boundaries
- [ ] **NIO event loop** *(6 days)*: accept/read/write, per-connection read buffer + write queue, partial writes via `OP_WRITE`, query/output buffer limits, idle timeout, graceful shutdown, 10 Hz cron hook
- [ ] `PING`, `ECHO`, `QUIT`, `COMMAND`, basic `INFO`, minimal `CLIENT`

**Done when**
- `redis-cli` connects and `PING` works
- `redis-benchmark -t ping -P 16` runs without errors
- Any valid byte stream parses identically however it is chunked

**Demo:** `redis-cli -p 6380 ping` → `PONG`.

---

### v0.2: It's a cache (7 days)
*Theme: strings and expiry. First version you could actually use.*

**Scope**
- [ ] Keyspace with `byte[]` key wrapper (cached hash)
- [ ] `SET` (EX/PX/NX/XX/GET), `GET`, `DEL`, `EXISTS`, `TYPE`
- [ ] `INCR/DECR/INCRBY/DECRBY`, `APPEND`, `STRLEN`, `GETRANGE`, `SETRANGE`, `MGET/MSET`
- [ ] `EXPIRE/PEXPIRE/EXPIREAT/TTL/PTTL/PERSIST`, stored as **absolute** timestamps
- [ ] Lazy expiry on access + **active expire cycle** (sample keys, repeat while many are expired)
- [ ] `KEYS`, `SCAN` (cursor-based), `FLUSHALL`, `DBSIZE`, `SELECT`

**Done when**
- An untouched expiring key actually disappears
- Oracle diffs pass on randomized string/expiry command sequences
- First benchmark table (yours vs real Redis) is in the README

**Demo:** use it as the cache behind a small script or app.

---

### v0.3: Lists, hashes, sets (6 days)
**Scope**
- [ ] Lists: `LPUSH/RPUSH/LPOP/RPOP/LLEN/LRANGE/LINDEX/LSET/LREM/LTRIM` (start on `ArrayDeque`)
- [ ] Hashes: `HSET/HGET/HDEL/HGETALL/HLEN/HEXISTS/HINCRBY`
- [ ] Sets: `SADD/SREM/SISMEMBER/SMEMBERS/SCARD/SINTER/SUNION/SDIFF`
- [ ] `WRONGTYPE` errors everywhere; type-aware `DEL`/`TYPE`/`EXPIRE`

**Done when:** all of the above match the oracle on randomized command sequences.

---

### v0.4: Sorted sets (7 days)
*Theme: the signature Redis data structure, written by you.*

**Scope**
- [ ] **Skip list** with rank spans + a dict for member→score
- [ ] `ZADD/ZREM/ZSCORE/ZRANK/ZREVRANK/ZRANGE/ZRANGEBYSCORE/ZINCRBY/ZCARD/ZCOUNT`
- [ ] Property test: skip list vs a `TreeMap` model under random operations
- [ ] JMH benchmark for the skip list

**Done when:** property tests and oracle diffs pass; rank queries are O(log n) in the benchmark.

---

### v0.5: Survives restarts, AOF (9 days)
**Scope**
- [ ] AOF writer: deterministic command form (relative expiries rewritten to absolute)
- [ ] fsync policies `always` / `everysec` / `no`
- [ ] AOF loader tolerant of a truncated last command
- [ ] **AOF rewrite** (compact the log from current state)
- [ ] Crash-recovery tests: `kill -9` mid-write, restart, compare state

**Done when:** `kill -9` at random moments never loses more than the configured fsync window.

**Demo:** write 1M keys, kill the process, restart, and `DBSIZE` matches.

---

### v0.6: Snapshots, RDB (9 days)
**Scope**
- [ ] Binary RDB format (writer + loader) with checksum
- [ ] **Stop-the-world snapshot first** (simple, correct)
- [ ] **Snapshot without `fork()`:** Java cannot fork. Experiment: snapshot incrementally across event-loop ticks, and when a write touches a key not yet saved, stash its old value first. Compare against stop-the-world and measure p99 latency
- [ ] Startup rules: prefer AOF if both exist; `SAVE`, `BGSAVE`-style trigger, auto-save interval

**Done when:** a snapshot of a large dataset completes without any single event-loop stall above your chosen threshold (pick a number from the stop-the-world baseline).

---

### v0.7: Bounded memory (7 days)
**Scope**
- [ ] Per-entry size estimation (the JVM gives no malloc stats)
- [ ] `maxmemory` + policies: `noeviction`, `allkeys-lru`, `volatile-lru`, `allkeys-lfu`, `allkeys-random`
- [ ] Approximated LRU (sample N keys, evict the oldest) and LFU with a Morris counter + decay
- [ ] `EvictionPolicy` as an **interface** (needed later for the AI twist)
- [ ] Hit-ratio comparison vs exact LRU on a synthetic trace

**Done when:** memory stays under the limit under sustained writes, and sampled-LRU hit ratio is close to exact LRU.

---

### v0.8: Pub/Sub, transactions, observability (8 days)
**Scope**
- [ ] `SUBSCRIBE/UNSUBSCRIBE/PUBLISH` (+ pattern subscriptions if time allows)
- [ ] `MULTI/EXEC/DISCARD/WATCH`
- [ ] `SLOWLOG` + HDR-style latency histograms, exposed in `INFO`

**Done when:** a subscriber gets published messages in order; `WATCH` aborts `EXEC` after a conflicting write.

---

### v0.9: Internals, incremental rehash (5 days)
**Scope**
- [ ] Replace the keyspace dict with your own **incrementally rehashed hash table**: two tables, move a few buckets per operation, so a resize never spikes latency
- [ ] Before/after p99 latency comparison during growth to millions of keys

**Done when:** p99 during a resize stays near steady-state, where the old dict showed a visible spike.

---

### v1.0: Stable base (5 days)
**Scope**
- [ ] README: architecture, supported commands, benchmark results vs real Redis
- [ ] Short write-up of the snapshot-without-fork design (good for a blog post or report)
- [ ] **Freeze the extension points:** command registry, `EvictionPolicy`, per-entry metadata, keyspace partitioning, big-value buffer path
- [ ] Cleanup pass on tests, flaky cases, logging

**Done when:** a fresh clone builds, passes all tests, and a new contributor can add a command by reading only the README.

---

### v1.1 (optional): I/O threads (6 days)
Parallelize socket reads/writes across I/O threads while command execution stays on the single thread (like Redis 6+). Only worth it if profiling shows I/O dominating.

---

### v2.x: AI-caching twist (planned after v1.0)
Driven by the earlier research. Candidate directions, to be chosen later:
- Tenant-safe, self-verifying semantic/tool-result cache (isolation by construction, freshness metadata on every hit, grey-zone verification)
- Workflow-aware eviction and prefetch for agent KV/prefix caches, evaluated by trace replay
- Large-chunk value path (fixed-size chunks, all-or-nothing retrieval) for KV-cache offload

The earliest sensible branch point is v0.7 (hooks exist), but v1.0 is cleaner.

---

## 5. Daily rhythm

1. **Learn first (2-2.5 h):** read the spec / Redis source / a paper for *today's* coding task.
2. **Code (2 h):** implement it the same day, while it's fresh.
3. **Last 10 min:** write tomorrow's first failing test, so tomorrow starts with momentum.
4. **Once a week:** run the oracle diff suite and `redis-benchmark`, and note the numbers.

Coding hours are the bottleneck, not learning. Use spare learning time to read real Redis source.

---

## 6. Learning plan per version

| Version | Read / watch |
|---|---|
| v0.1 | RESP specification (redis.io docs); Java NIO (Selector, ByteBuffer, non-blocking channels); Redis `networking.c` and `ae.c`; "Build Your Own Redis" (build-your-own.org) for event-loop ideas |
| v0.2 | Redis `db.c`; `expire.c` (active expiry cycle) |
| v0.3 | Redis `t_list.c`, `t_hash.c`, `t_set.c` |
| v0.4 | William Pugh, *Skip Lists: A Probabilistic Alternative to Balanced Trees*; Redis `t_zset.c` |
| v0.5 | Redis docs on persistence (AOF); `aof.c` |
| v0.6 | Redis docs on RDB; `rdb.c`; how fork + copy-on-write works, to understand what you're replacing |
| v0.7 | Redis docs on eviction; `evict.c`; Morris counter (approximate counting) |
| v0.8 | Redis docs on Pub/Sub and transactions; HDR histogram idea |
| v0.9 | Redis `dict.c` (incremental rehash) |
| v1.0 | How to write a good README / benchmark report |

---

## 7. Testing and benchmarking (all versions)

- **Oracle diff tests:** the same randomized command sequence against real Redis and your server; compare replies.
- **Parser fuzzing:** random split points and malformed input.
- **Property tests:** data structures vs simple reference models (e.g. skip list vs `TreeMap`).
- **Crash tests (v0.5+):** `kill -9` during writes, then restart and compare state.
- **Benchmarks:** `redis-benchmark` against real Redis on the same machine (compare ratios, not absolute numbers); JMH for the skip list and dict.
- **Watch p99 latency**, not just throughput. GC pauses matter once you hold millions of keys.

---

## 8. Hooks to leave for the AI-caching twist (cheap now, costly to retrofit)

| Hook | Introduced |
|---|---|
| Command registry that lets a module register new commands | v0.1 |
| Buffer layer able to read a large bulk value straight into its destination (no extra copies for multi-MB values) | v0.1 |
| Per-entry metadata fields (even if unused at first) | v0.2 |
| Keyspace abstraction that could later be partitioned per tenant | v0.2 |
| Pluggable eviction and expiry policies | v0.7 |

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Parser bugs on partial reads | Fuzz with random chunk boundaries from v0.1 |
| Slow client memory blowup | Output-buffer limits in v0.1, not later |
| GC pauses with big heaps | Measure from v0.2; keep values as `byte[]`; consider off-heap only if data demands it |
| Snapshot design rabbit hole | Ship stop-the-world in v0.6 first; treat the incremental design as an upgrade |
| Scope creep | Each version has a "done when"; don't start the next until it passes |

---

## 10. Mapping from the earlier phase plan

| Old phase | Now |
|---|---|
| Phase 0 + 1 + 2 | v0.1 |
| Phase 3 | v0.2 |
| Phase 4 | v0.3 (lists, hashes, sets) + v0.4 (sorted sets) |
| Phase 5 | v0.5 (AOF) + v0.6 (RDB) |
| Phase 6 | v0.7 |
| Phase 7 deep-dives | v0.8, v0.9, v1.1 |
| Polish and write-up | v1.0 |
