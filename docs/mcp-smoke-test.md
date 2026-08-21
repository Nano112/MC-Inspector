# MCP host smoke test

Run `scripts/run-client.sh start`, then drive these through any MCP client (Claude Code with the repo's `.mcp.json`,
or `bun scratch/mcp.mjs <tool> '<json>'`). All of these passed on 1.21.11 on 2026-08-22.

- [ ] `screenshot {scale:0.5}` → image of the title screen; text block has `mouseScale`.
- [ ] `get_state` → `screen: TitleScreen`, `inWorld: false`, window sizes.
- [ ] `read_logs {filter:"MCInspector"}` → the "listening on" line.
- [ ] `list_mods` → includes `mcinspector`, `fabric-api`.
- [ ] `list_widgets` → Singleplayer / Multiplayer / Options... with GUI bounds.
- [ ] `click_widget {text:"Options"}` → `screenAfter: OptionsScreen`; `click_widget {text:"Done"}` → back to title.
- [ ] `click_widget {text:"Multiplayer"}` → SafetyScreen → `click_widget {text:"Proceed"}` → JoinMultiplayerScreen.
- [ ] `click_widget {text:"Add Server"}` → `set_text_field {text:"Server Address", value:"localhost:25565"}`;
      `set_text_field {text:"Server Name", value:""}` + `type_text {text:"Local Test"}` → `click_widget {text:"Done"}` → `list_servers` shows it.
- [ ] `press_key {key:"escape"}` → title screen.
- [ ] `click_widget {text:"Singleplayer"}` → select a `WorldListEntry` by index → `click_widget {text:"Play Selected World"}`
      (disabled until an entry is selected — the error message says so) → poll `get_state` until `inWorld`.
- [ ] `hold_key w` / `wait_ticks 40` / `release_key w` → position changes (`hold_key` reports `key.forward: down`).
- [ ] `mouse_move {x:400,y:0,relative:true}` → yaw changes.
- [ ] `press_key {key:"space", holdTicks:2}` → y changes briefly.
- [ ] `press_key e` → `get_screen` is InventoryScreen; `press_key escape` closes it.
- [ ] `run_command {command:"/time set day"}` and `{command:"hello"}` → lines appear in `read_logs {filter:"CHAT"}`.
- [ ] `open_screen pause` → `screenshot` shows the pause menu.
- [ ] `disconnect` → `get_state` is TitleScreen, `inWorld: false`.
