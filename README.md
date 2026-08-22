# MC-Inspector

A Fabric client mod that lets an AI agent drive and debug Minecraft, plus a packet inspector — both in-game
(via the [panel-lib](https://github.com/Nano112/panel-lib) overlay) and over [MCP](https://modelcontextprotocol.io).

[![build](https://github.com/Nano112/MC-Inspector/actions/workflows/build.yml/badge.svg)](https://github.com/Nano112/MC-Inspector/actions/workflows/build.yml)
Minecraft 1.21.8 – 1.21.11, 26.1, 26.2 · Fabric · Kotlin · MIT

## What you get

- **MCP host** on `http://127.0.0.1:38271/mcp` (loopback only) with 29 tools: `screenshot` (what's on screen,
  overlays included), `get_state`, `read_logs`, keyboard/mouse injection, vanilla-menu helpers
  (`list_widgets`, `click_widget`, `set_text_field`), `run_command`, `connect_server`, `run_sequence` (many steps,
  one call, with `waitFor`), `packets_*`, and `exec_script` (JavaScript inside the client).
  Catalog: [docs/mcp-tools.md](docs/mcp-tools.md).
- **Packet inspector**: every client↔server packet, both directions, all phases, singleplayer included — decoded
  fields, byte sizes + hex on real connections, regex/deep filters with presets, markers, search, `packets_watch`,
  diffs, rate graphs, and **rules**: breakpoints (`PAUSE` → hold, resume, drop), `DROP`, and JS `SCRIPT` rules that
  can rewrite inbound/outbound packets (`set(packet, "health", 20)`). Panel key **O** (Live / Types / Graph / Rules tabs).
- **MCP Host panel** (key **I**): port, tool-call counters. Overlay key **K** (panel-lib).

## Install

Drop the jar for your Minecraft version from [Releases](https://github.com/Nano112/MC-Inspector/releases) into
`mods/` — panel-lib and the JS engine are bundled. Requires Fabric API + Fabric Language Kotlin.

Config `config/mcinspector.json`: `port`, `auth_token`, `screenshot_scale`, `enabled`, `cursor_lock`
(`auto` default: vanilla cursor lock except for a few seconds after the agent injected input; `always` / `never`),
`packet_capture` (default `false` — recording starts only when you press Start / `packets_capture {paused:false}`;
rules run regardless), `packet_buffer` (rolling window, default 5000), `packet_retain` (newest records keeping live
packet objects, default 1000; older ones are materialised to JSON so memory stays bounded).

## Connect an agent

```bash
claude mcp add --transport http mcinspector http://127.0.0.1:38271/mcp   # or use this repo's .mcp.json
```

Typical loop: `screenshot` → `list_widgets` / `click_widget` for menus, `press_key` / `mouse_click` in-world
(`mouse_click` settles two ticks after moving so ImGui can claim the click), `read_logs` to verify, `run_sequence`
to batch a whole flow (e.g. navigate menus → load world → screenshot).

## Develop

```bash
scripts/run-client.sh start|stop|status|log     # dev client, waits for /health
scripts/run-server.sh start|stop                # dev server on localhost:25565 for real-network packets
./gradlew :fabric:1.21.11:build                 # one version + tests
./gradlew :fabric:buildAllVersions              # all versions → build/libs/<version>/
./gradlew :fabric:1.21.11:generateToolDocs      # regenerate docs/mcp-tools.md (a test enforces parity)
scripts/refresh-panellib.sh                     # after changing ../panel-lib (evicts Loom's remap cache)
```

The game dir is `fabric/run`. panel-lib is resolved from mavenLocal (`../panel-lib` → `publishToMavenLocal`) or
GitHub Packages; CI checks out panel-lib next to this repo and builds it first.

Design notes and plans live in `docs/superpowers/`.
