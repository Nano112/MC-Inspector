# Packet inspector — design (sub-project 3 of 3)

Date: 2026-08-22 · Status: implemented 2026-08-22 (6 versions build; verified live in singleplayer)

## What

Capture every client↔server packet (both directions, all protocol phases, network AND singleplayer in-memory
connections), keep the recent ones in a ring buffer with a decoded view, and expose them two ways:
a panel-lib **Packets** panel for humans and `packets_*` MCP tools for agents.

## Decisions

| Topic | Decision |
|---|---|
| Hook | Mixins on `net.minecraft.network.Connection`: `channelRead0(ctx, Packet)` HEAD = inbound, `doSendPacket(Packet, listener, flush)` HEAD = outbound. Same signatures on 1.21.8–26.2; covers the in-memory singleplayer pipeline too. |
| Record | `id` (monotonic), `t` (epoch ms), `tick`, `dir` (IN/OUT), `phase` (from the packet's package: handshake/status/login/configuration/game/common/cookie/ping), `type` (`packet.type().id()` e.g. `minecraft:set_entity_motion`), `cls` (simple class name), `summary` (one line), decoded `fields` (lazy, cached). Size is not captured in v1 (the encoder runs later on another thread; in-memory connections never encode). |
| Decode | Reflective: record components first, else declared fields up the hierarchy; depth ≤ 4, ≤ 32 elements per collection/array, strings ≤ 200 chars, known leaf types (numbers, String, UUID, Identifier, BlockPos, Vec3, enums, Component→string) rendered directly. Never throws — failures become `"<error: …>"`. |
| Side | Each record carries `side` CLIENT/SERVER (a connection that *receives* clientbound packets is the client); the integrated server's connections are skipped unless `packets_capture {serverSide:true}`. |
| Store | Ring buffer (default 5000, config `packet_buffer`), per-type counters (since clear), capture `paused` flag, `exclude` type patterns (config + runtime) to drop noise. Thread-safe (netty + render + http threads). |
| MCP tools | `packets_list {limit≤500, direction, phase, type (regex), since (id), newestFirst}` → compact rows; `packets_get {id}` → full decoded fields; `packets_stats` → counts per type + totals + paused; `packets_clear`; `packets_capture {paused?, exclude?: [regex]}`. |
| Panel | "Packets" (key **O**; P is vanilla Social Interactions): toolbar — pause/resume, clear, IN/OUT toggles, filter text (type regex), autoscroll; striped table (#, time, dir, type, summary); click a row → detail child with the decoded field tree (collapsible). Updates every frame from a snapshot. |
| Perf | Recording is O(1) (timestamp + type id); decoding is lazy on first view. Panel renders at most the last N=400 filtered rows. |

## Files

```
fabric/src/client/kotlin/dev/harrison/mcinspector/packets/
  PacketRecord.kt     data + Direction/Phase enums + phaseOf(className)
  PacketDecoder.kt    reflective decode → JsonElement (pure Kotlin, unit-tested)
  PacketStore.kt      ring buffer, filters, stats, paused/exclude (pure Kotlin, unit-tested)
  PacketCapture.kt    static entry for mixins: record(packet, dir) → store (reads tick, type id)
fabric/src/client/java/dev/harrison/mcinspector/mixin/ConnectionMixin.java
fabric/src/client/kotlin/dev/harrison/mcinspector/tools/PacketTools.kt
fabric/src/client/kotlin/dev/harrison/mcinspector/ui/PacketsPanel.kt   (registered from InspectorPanels)
```

## Out of scope (v1)

Byte sizes / raw hex, packet editing/injection, replay, persistent capture files.
