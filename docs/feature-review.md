# Feature review — September 2026

MC-Inspector already supports screenshots including overlays, client/menu/input automation,
JavaScript scripting, decoded and raw packet inspection, markers, regex/deep search, packet
field diffs, rate charts, breakpoints, drop/rewrite rules, and JSONL/CSV/PCAP export.

The first release adds a capture bundle: a ZIP with a bounded packet snapshot, markers,
client/version context, optional PNG, and explicit capture-loss/selection metadata. It is
available through `capture_bundle` and the Packets panel's Save capture bundle button.
This is a convenient saved evidence package, not a continuous session recorder.

## Most useful next features for DisplayKit / Hardwired investigation

1. **Entity inspector and timeline.** Track spawn, metadata, transforms, interpolation,
   movement and removal for one entity ID. Resolve display metadata into meaningful
   names and show parent/passenger relationships. Current packet search and diffs are
   generic; they do not reconstruct an entity's lifecycle.
2. **Offline capture browser.** Import an exported JSONL/bundle, filter and compare it,
   and link timeline markers to screenshots without reconnecting to the server.
   Current exports are readable by external tools, but cannot be reopened in the panel.
3. **Continuous sessions.** Record longer investigations to disk with rotation, limits,
   pause/resume history and multiple screenshot markers. The current rolling buffer
   evicts old records; the bundle accurately reports this instead of promising a full session.
4. **Resource-pack and model inventory.** Identify active packs, hashes, model references,
   custom-model-data mappings and display items. This would help connect visible effects
   to Blockbench/item-model assets. It is not implemented in this release.
5. **Visual summaries.** Correlate particles, sounds, bossbars and display packets around
   markers; compare two captures and produce a compact effect recipe for DisplayKit.

Packet replay/editing should remain a separate, explicit workflow. A decoded packet
capture does not reveal a server's source code or prove its internal implementation.

## Runtime follow-up

Automated clicks on docked controls were intermittent during resize/focus transitions in the
macOS development client. Investigate input settling and game/overlay focus with panel-lib.
Direct MCP packet and capture-bundle tools worked independently of those UI clicks.
