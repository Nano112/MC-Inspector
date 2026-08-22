# MC-Inspector

Client-side Fabric mod for agent-driven game debugging. Two faces:

1. **MCP host** (done) — an MCP server embedded in the client so an AI agent can screenshot the game, read state and
   logs, inject keyboard/mouse input, drive vanilla menus, run commands and connect to servers.
2. **Packet inspector** (done) — every client↔server packet (both directions, all phases, network and singleplayer)
   captured into a ring buffer with lazy reflective decoding. Humans get the **Packets** panel (key `O`, in the shared
   [panel-lib](../panel-lib) overlay, key `K`): pause/clear, IN/OUT toggles, regex filter, live table, decoded field
   tree. Agents get `packets_list / packets_get / packets_stats / packets_clear / packets_capture` (pause, exclude
   regexes, integrated-server side).

Supported: Minecraft 1.21.8 – 1.21.11, 26.1, 26.2 (Stonecutter; active dev version 1.21.11; embedded layout verified live on 1.21.11 and 26.2).

## Run

```bash
scripts/run-client.sh start        # ./gradlew :fabric:runClient detached, waits for /health
scripts/run-client.sh status|log|stop
```

The server listens on `http://127.0.0.1:38271/mcp` (loopback only). The port is written to `fabric/run/.mcp-port` (the game dir is `fabric/run`) and logged as
`[MCInspector] MCP server listening on ...`. Configure in `fabric/run/config/mcinspector.json`
(`port`, `auth_token`, `screenshot_scale`, `enabled`) or `-Dmcinspector.port=… -Dmcinspector.enabled=false`.

## Connect an agent

The repo ships a `.mcp.json`, so Claude Code picks the server up automatically in this directory. Elsewhere:

```bash
claude mcp add --transport http mcinspector http://127.0.0.1:38271/mcp
```

Tool catalog: [docs/mcp-tools.md](docs/mcp-tools.md) (regenerate with `./gradlew :fabric:1.21.11:generateToolDocs`). Manual checklist: [docs/mcp-smoke-test.md](docs/mcp-smoke-test.md).

## Dev server (real network packets)

```bash
scripts/run-server.sh start        # Fabric dev server on localhost:25565 (online-mode=false), same fabric/run dir
```
then `connect_server localhost:25565`. Byte sizes in `packets_*` are only available on real connections (singleplayer
is in-memory and never encodes).

## Scripting

`exec_script` runs JavaScript (Rhino) inside the client with `mc`, `player`, `level`, `window`, `packets`, `panellib`,
`tools.call(name, args)` and `log(...)`; Java is reachable via `Packages.<fqcn>` / `importClass`. A deadline aborts runaway
scripts. Example: `tools.call("get_state").player.x` or
`importClass(Packages.net.minecraft.network.chat.Component); mc.gui.getChat().addMessage(Component.literal("hi"))`.

## Keys

`K` toggle the panel-lib overlay · `I` MCP Host panel · `O` Packets panel (rebindable under Options → Controls → panel-lib).
With the overlay open the game is **embedded** in the central area (panels sit beside it, not over it); toggle in Layout ▾.

## Notes for agents

- `run_sequence` runs many tools in one call with `waitTicks` / `waitFor` — use it for menu flows and world loads.
- The OS cursor is never locked while the host runs (`lock_cursor` config), so a human can keep using the machine.

- While the MCP host is enabled the game reports its window as focused, so it keeps running and accepts mouse-look
  even when another app is in front.
- Input is injected at Minecraft's `KeyboardHandler`/`MouseHandler` level, so it reaches the open screen, key bindings
  and other mods' input mixins exactly like real input.
- Tool errors come back as `isError` results with a readable message (e.g. which widgets exist when a label doesn't match).

## Build

```bash
./gradlew :fabric:1.21.11:build         # one version (+ unit tests)
./gradlew :fabric:buildAllVersions      # every supported version -> build/libs/<version>/
```

Design docs live in `docs/superpowers/specs/`, plans in `docs/superpowers/plans/`.
