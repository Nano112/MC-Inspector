# MC-Inspector

Client-side Fabric mod for agent-driven game debugging. Two faces:

1. **MCP host** (done) — an MCP server embedded in the client so an AI agent can screenshot the game, read state and
   logs, inject keyboard/mouse input, drive vanilla menus, run commands and connect to servers.
2. **Packet inspector** (planned) — capture, decode and inspect client↔server packets, with an ImGui UI built on the
   shared [panel-lib](../panel-lib) overlay and exposed to agents through the same MCP server.

Supported: Minecraft 1.21.8 – 1.21.11, 26.1, 26.2 (Stonecutter; active dev version 1.21.11).

## Run

```bash
scripts/run-client.sh start        # ./gradlew :fabric:runClient detached, waits for /health
scripts/run-client.sh status|log|stop
```

The server listens on `http://127.0.0.1:38271/mcp` (loopback only). The port is written to `run/.mcp-port` and logged as
`[MCInspector] MCP server listening on ...`. Configure in `run/config/mcinspector.json`
(`port`, `auth_token`, `screenshot_scale`, `enabled`) or `-Dmcinspector.port=… -Dmcinspector.enabled=false`.

## Connect an agent

The repo ships a `.mcp.json`, so Claude Code picks the server up automatically in this directory. Elsewhere:

```bash
claude mcp add --transport http mcinspector http://127.0.0.1:38271/mcp
```

Tool catalog: [docs/mcp-tools.md](docs/mcp-tools.md). Manual checklist: [docs/mcp-smoke-test.md](docs/mcp-smoke-test.md).

## Notes for agents

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
