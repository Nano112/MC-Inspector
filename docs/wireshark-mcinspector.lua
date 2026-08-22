-- Wireshark dissector for MC-Inspector pcap exports (link type 147 / DLT_USER0).
-- Install: copy to ~/.local/lib/wireshark/plugins/ (Linux/macOS) or %APPDATA%\Wireshark\plugins\ (Windows), restart Wireshark.
-- Frame layout: 1 byte direction (0 = server→client, 1 = client→server), 4 bytes big-endian MC-Inspector record id,
-- then the raw Minecraft wire payload: packet id (VarInt) + body (uncompressed, unencrypted).
local proto = Proto("mcinspector", "MC-Inspector Minecraft packet")
local f_dir = ProtoField.uint8("mcinspector.direction", "Direction", base.DEC, { [0] = "server→client (IN)", [1] = "client→server (OUT)" })
local f_id = ProtoField.uint32("mcinspector.record", "Record id", base.DEC)
local f_pid = ProtoField.uint32("mcinspector.packet_id", "Packet id (VarInt)", base.HEX)
local f_body = ProtoField.bytes("mcinspector.body", "Body")
proto.fields = { f_dir, f_id, f_pid, f_body }

local function read_varint(tvb, off)
  local value, shift, n = 0, 0, 0
  repeat
    local b = tvb(off + n, 1):uint()
    value = value + bit.lshift(bit.band(b, 0x7f), shift)
    shift = shift + 7; n = n + 1
  until bit.band(b, 0x80) == 0 or n >= 5
  return value, n
end

function proto.dissector(tvb, pinfo, tree)
  pinfo.cols.protocol = "MC"
  local t = tree:add(proto, tvb())
  local dir = tvb(0, 1):uint()
  t:add(f_dir, tvb(0, 1))
  t:add(f_id, tvb(1, 4))
  local pid, n = read_varint(tvb, 5)
  t:add(f_pid, tvb(5, n), pid)
  if tvb:len() > 5 + n then t:add(f_body, tvb(5 + n)) end
  pinfo.cols.info = string.format("%s packet 0x%02x (%d bytes) #%d", dir == 0 and "IN " or "OUT", pid, tvb:len() - 5 - n, tvb(1, 4):uint())
end

DissectorTable.get("wtap_encap"):add(wtap.USER0, proto)
