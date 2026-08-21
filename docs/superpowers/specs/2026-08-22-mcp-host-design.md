# MC-Inspector — MCP Host design (sub-project 1 of 3)

Date: 2026-08-22
Status: approved in brainstorm (verbal), building

## Program overview

MC-Inspector is a client-side Fabric mod with two faces:

1. **Packet inspector** — capture, decode, filter and inspect client<->server packets (sub-project 3).
2. **MCP host** — an MCP server embedded in the game client so an AI agent can drive the instance
   autonomously: screenshots, keyboard/mouse, menus, commands, state, logs (this document).

A third, standalone library, **panel-lib** (`../panel-lib`, sub-project 2), is the shared Dear ImGui
overlay extracted from SchematioConnector. The packet inspector UI is built on it.

Build order is **MCP host → panel-lib → packet inspector**: the MCP host is the dogfooding harness
that lets the agent see and drive the game while building the other two.

## Decisions (from brainstorm)

| Topic | Decision |
|---|---|
| Language | Kotlin (Fabric Language Kotlin), Java for mixins |
| Group / package | `dev.harrison.mcinspector` |
| Mod id | `mcinspector` |
| Versions | Stonecutter, same set as SchematioConnector: 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.2. Active dev version **1.21.11**. |
| MCP transport | Streamable HTTP (JSON-RPC over POST) on `127.0.0.1`, hand-rolled with JDK `com.sun.net.httpserver` + Gson (MC ships Gson). No MCP SDK. |
| Port | Default `38271`, override via `-Dmcinspector.port=` or `config/mcinspector.json`. Registered in the local gerry registry. Actual port written to `<runDir>/.mcp-port` on bind. |
| Tool scope (v1) | Observe + control (option B). `exec_script` (JavaScript) deferred to a later version. |
| Input injection | MC handler layer: `KeyboardHandler.keyPress/charTyped`, `MouseHandler.onPress/onButton/onMove`, executed on the render thread. Movement via `KeyMapping.set`. |
| Menus | Raw input reaches the open `Screen` through the same handlers; plus semantic `list_widgets` / `click_widget` that walk `Screen.children()`. |
| Security | Loopback bind only; optional bearer token (`auth_token` in config, off by default). |

## Repository layout

```
MC-Inspector/
  settings.gradle.kts          stonecutter { create(":fabric") { versions(...) ; vcsVersion = "1.21.11" } }
  build.gradle.kts             plugin versions (kotlin 2.4.0, fabric-loom 1.16-SNAPSHOT, stonecutter 0.9.5)
  gradle.properties            shared pins (loader, FLK, java), mod version
  fabric/
    stonecutter.gradle.kts     active version, buildAllVersions, runClient delegation
    build.gradle.kts           per-version build (copied/trimmed from SchematioConnector)
    versions/<ver>/gradle.properties   deps.fabric_api, mod.mc_compat
    src/main/resources/fabric.mod.json, mcinspector.client.mixins.json
    src/client/kotlin/dev/harrison/mcinspector/
      McInspectorClient.kt                ClientModInitializer: load config, start McpServer, register shutdown
      config/InspectorConfig.kt           port, auth_token, screenshot defaults; JSON in config/mcinspector.json
      mcp/
        McpServer.kt                      HttpServer on 127.0.0.1:port; POST /mcp; GET /health
        JsonRpc.kt                        request/response/error envelopes, batch handling
        McpProtocol.kt                    initialize, ping, tools/list, tools/call, notifications/initialized
        Tool.kt                           interface Tool { name, description, inputSchema, call(args): ToolResult }
        ToolRegistry.kt                   name -> Tool
        ToolResult.kt                     text / image content blocks, isError
        RenderThread.kt                   runOnRenderThread(timeout) { } -> result, via Minecraft.execute + CompletableFuture
      tools/
        ScreenshotTool.kt                 screenshot
        StateTool.kt                      get_state
        LogsTool.kt                       read_logs
        ModsTool.kt                       list_mods
        InputTools.kt                     press_key, hold_key, release_key, type_text, mouse_move, mouse_click, mouse_scroll
        ScreenTools.kt                    get_screen, list_widgets, click_widget, set_text_field, open_screen, close_screen
        CommandTool.kt                    run_command (chat or /command)
        ServerTools.kt                    connect_server, disconnect, list_servers
        WaitTool.kt                       wait_ticks
      input/
        InputInjector.kt                  Kotlin facade over the (per-version) keyboard/mouse handler calls
        KeyNames.kt                       "key.keyboard.x" / "ESCAPE" / "a" -> GLFW code
      log/
        LogRingBuffer.kt                  log4j appender capturing last N lines
    src/client/java/dev/harrison/mcinspector/mixin/
      KeyboardHandlerAccessor.java        @Invoker keyPress/charTyped (version-split with //? markers)
      MouseHandlerAccessor.java           @Invoker onPress/onButton/onMove/onScroll (version-split)
  scripts/run-client.sh                  ./gradlew :fabric:runClient in background, tails log, prints port
  docs/superpowers/specs/, docs/mcp-tools.md (generated catalog of tools)
```

## MCP transport

- `POST /mcp` with `Content-Type: application/json`. Body is one JSON-RPC 2.0 request or a batch.
  Response is `application/json` (we do not open SSE streams in v1; clients that send
  `Accept: text/event-stream` still get JSON, which the spec allows for single responses).
- Notifications (no `id`) return `202 Accepted` with empty body.
- `GET /mcp` returns `405` (no server-initiated stream in v1). `DELETE /mcp` returns `200`.
- `Mcp-Session-Id` is issued on `initialize` and accepted but not enforced (single-client dev tool).
- `GET /health` returns `{ "ok": true, "mc": "<version>", "tools": n }` — used by the run script.
- Methods: `initialize` → `{ protocolVersion: "2025-06-18", capabilities: { tools: {} }, serverInfo }`;
  `notifications/initialized` → 202; `ping` → `{}`; `tools/list` → `{ tools: [...] }`;
  `tools/call` → `{ content: [...], isError }`. Unknown method → `-32601`.
- Errors inside a tool become `isError: true` with the exception message as text — never a JSON-RPC error,
  so the agent can read them.
- HTTP handler runs on a small cached thread pool. Anything that touches the game goes through
  `RenderThread.run(timeoutMs) { }`; default timeout 10 s; timeout → isError result.

## Tools catalog (v1)

All tools return a `text` block containing a JSON object unless noted. Coordinates for the mouse are
in **window pixels** (same space the screenshot is in); the screenshot result reports the scale used so
the agent can map coordinates. GUI-scale coordinates are also returned by `list_widgets` so clicks can
use either (`click_widget` handles it).

| Tool | Input | Output |
|---|---|---|
| `screenshot` | `scale?` (0–1, default from config, 0.5), `format?` ("png") | `image` block (base64 PNG) + text `{width,height,scale,windowWidth,windowHeight,guiScale}` |
| `get_state` | — | `{screen, inWorld, player{pos,yaw,pitch,health,food,dimension,gamemode}, server{address,name}|null, fps, tick, window{width,height,guiScale,focused,mouseGrabbed}}` |
| `read_logs` | `lines?` (default 100, max 1000), `filter?` (regex), `level?` | `{lines:[...]}` from ring buffer |
| `list_mods` | — | `[{id,version,name}]` |
| `press_key` | `key` (GLFW name e.g. `"e"`, `"escape"`, `"f3"`, `"left_shift"`), `modifiers?` [ctrl,shift,alt], `holdTicks?` (default 1) | press then release after N ticks |
| `hold_key` / `release_key` | `key` | for movement: sets the matching `KeyMapping` down/up in addition to the handler call |
| `type_text` | `text` | charTyped per code point (plus keyPress for enter/tab if in text) |
| `mouse_move` | `x`, `y` (window px), `relative?` | in-world with grabbed mouse: applies as look delta |
| `mouse_click` | `x?`, `y?`, `button?` (left/right/middle), `double?` | moves then press+release |
| `mouse_scroll` | `dy`, `x?`, `y?` | |
| `get_screen` | — | `{className, title, width, height}` or null |
| `list_widgets` | — | `[{index, type, text, x, y, width, height, active, focused, visible}]` (recursive through ContainerEventHandler) |
| `click_widget` | `index?` or `text?` (case-insensitive contains) | clicks centre of the widget via mouse path |
| `set_text_field` | `index?`/`text?`, `value` | focuses EditBox and types the value |
| `open_screen` | `id` in {`title`, `pause`, `options`, `controls`, `multiplayer`, `singleplayer`, `inventory`, `chat`} | |
| `close_screen` | — | `setScreen(null)` |
| `run_command` | `command` (with or without leading `/`), or plain chat | sends via `connection.sendCommand/sendChat` |
| `connect_server` | `address` | `ConnectScreen.startConnecting` |
| `disconnect` | — | leaves world/server to title |
| `list_servers` | — | saved server list |
| `wait_ticks` | `ticks` (max 1200) | resolves after N client ticks; use to let UI settle |

`docs/mcp-tools.md` is generated from the registry (`./gradlew :fabric:generateToolDocs` is a
nice-to-have; v1 writes it by hand and a test asserts parity with the registry).

## Input engine details

- Key names resolve through `InputConstants.getKey("key.keyboard.<name>")` when possible, with a
  fallback map for friendly aliases. Unknown key → isError.
- Press path: `KeyboardHandler.keyPress(window, key, scancode, GLFW_PRESS, mods)` then, `holdTicks`
  later (scheduled on client tick), `GLFW_RELEASE`. On 1.21.9+ the signature takes a `KeyEvent`; an
  accessor mixin with `//?` splits wraps the difference (pattern copied from SchematioConnector's
  `KeyboardMixin`).
- Mouse: `MouseHandler.onMove(window,x,y)` then `onPress(window,button,GLFW_PRESS,mods)` (`onButton` with
  `MouseButtonInfo` on 1.21.9+). When the mouse is grabbed (in-world), `mouse_move relative` feeds
  `handleAccumulatedMovement` via an accumulated delta.
- Movement keys when in-world: `hold_key` also sets `KeyMapping.set(InputConstants.Key, true)` so the
  player actually walks (the handler path alone only toggles `KeyMapping` via GLFW events on some versions).
- Everything runs on the render thread; a tick scheduler (`ClientTickEvents.END_CLIENT_TICK`) handles
  delayed releases and `wait_ticks`.

## Screenshot

`Screenshot.takeScreenshot(minecraft.mainRenderTarget) { NativeImage -> }` on the render thread, scaled
with `NativeImage.resizeSubRectTo` (or manual nearest sampling if unavailable), encoded to PNG bytes
via `NativeImage.asByteArray()`, base64 into an `image` content block. 26.x uses a different
readback path; handled with `//?` splits (SchematioConnector's `OffscreenTarget.kt` documents both).

## Logging

A log4j2 `Appender` attached to the root logger at mod init stores the last 2000 formatted lines in a
ring buffer. `read_logs` filters by level/regex. Also the MCP server logs each tool call at DEBUG.

## Config

`config/mcinspector.json`: `{ "port": 38271, "auth_token": null, "screenshot_scale": 0.5, "enabled": true }`.
`-Dmcinspector.port` and `-Dmcinspector.enabled` override. On bind, write `<runDir>/.mcp-port` and log
`[MCInspector] MCP server listening on http://127.0.0.1:<port>/mcp`.

## Run script & agent workflow

`scripts/run-client.sh [start|stop|status|log]`: starts `./gradlew :fabric:runClient` detached with
output to `run/mcinspector-client.log`, waits for `/health`, prints the port. `claude mcp add --transport
http mcinspector http://127.0.0.1:38271/mcp` connects Claude Code.

## Testing

- Unit (JUnit5, `fabric/src/test`): JSON-RPC parsing/batching, `McpProtocol` method dispatch with a fake
  tool, `KeyNames` resolution table, `LogRingBuffer`, tool-docs parity.
- Integration (manual + agent-driven): run client, `initialize`, `screenshot`, `open_screen options`,
  `list_widgets`, `click_widget "Done"`, `connect_server localhost`, `run_command`. Documented as a
  checklist in `docs/mcp-smoke-test.md` and executed by the agent as the dogfooding loop.

## Out of scope (v1)

`exec_script` (JavaScript), SSE/server-initiated notifications, MCP resources/prompts, multi-client
sessions, non-loopback binding, packet tools (sub-project 3 will add `packets_*` tools to this server).
