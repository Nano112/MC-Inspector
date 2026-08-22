package dev.harrison.mcinspector.packets

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exports captured packets for external tools.
 *  - JSONL: one JSON object per line with decoded fields (jq / Python / pandas).
 *  - CSV: id, time, dir, side, phase, type, class, bytes, status, summary (spreadsheets).
 *  - PCAP: classic libpcap, link type 147 (LINKTYPE_USER0). Each record = 1 byte direction (0 = server→client,
 *    1 = client→server) + 4-byte big-endian record id + the raw wire payload (packet id varint + body, i.e. what
 *    PacketDecoder/PacketEncoder saw, uncompressed, unencrypted). Only packets from real network connections
 *    carry bytes; in-memory (singleplayer) ones are skipped. Load in Wireshark with a Lua dissector for DLT_USER0
 *    or inspect with scapy/tshark as raw frames.
 */
object PacketExport {
    enum class Format(val ext: String) { JSONL("jsonl"), CSV("csv"), PCAP("pcap") }

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    data class Result(val path: Path, val records: Int, val bytes: Long)

    fun defaultPath(dir: Path, format: Format): Path = dir.resolve("packets-${FILE_TS.format(Instant.now())}.${format.ext}")

    fun export(records: List<PacketRecord>, format: Format, out: Path, fields: Boolean = true): Result {
        Files.createDirectories(out.parent ?: out.toAbsolutePath().parent)
        var n = 0
        when (format) {
            Format.JSONL -> Files.newBufferedWriter(out).use { w ->
                for (r in records) {
                    if (r.isMarker) { w.write(gson.toJson(JsonObject().apply { addProperty("id", r.id); addProperty("marker", r.type.removePrefix("marker:")); addProperty("epochMs", r.epochMs); addProperty("tick", r.tick) })); w.newLine(); n++; continue }
                    val o = JsonObject().apply {
                        addProperty("id", r.id); addProperty("epochMs", r.epochMs); addProperty("time", TS.format(Instant.ofEpochMilli(r.epochMs))); addProperty("tick", r.tick)
                        addProperty("dir", r.dir.name); addProperty("side", r.side.name); addProperty("phase", r.phase.name)
                        addProperty("type", r.type); addProperty("class", r.cls); r.size?.let { addProperty("bytes", it) }
                        if (r.status != RecordStatus.NORMAL) addProperty("status", r.status.name)
                        if (r.ruleHits.isNotEmpty()) add("rules", JsonArray().apply { r.ruleHits.forEach { add(JsonPrimitive(it)) } })
                        addProperty("summary", r.summary)
                        if (fields) add("fields", r.fields)
                        r.raw?.let { addProperty("rawHex", it.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }) }
                    }
                    w.write(gson.toJson(o)); w.newLine(); n++
                }
            }
            Format.CSV -> Files.newBufferedWriter(out).use { w ->
                w.write("id,time,epochMs,tick,dir,side,phase,type,class,bytes,status,rules,summary\n")
                for (r in records) {
                    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
                    w.write(listOf(
                        r.id.toString(), TS.format(Instant.ofEpochMilli(r.epochMs)), r.epochMs.toString(), r.tick.toString(),
                        r.dir.name, r.side.name, r.phase.name, q(r.type), q(r.cls), r.size?.toString() ?: "",
                        if (r.status == RecordStatus.NORMAL) "" else r.status.name, q(r.ruleHits.joinToString(";")), q(r.summary),
                    ).joinToString(",")); w.newLine(); n++
                }
            }
            Format.PCAP -> DataOutputStream(Files.newOutputStream(out).buffered()).use { d ->
                // Global header (big-endian magic → readers treat fields as big-endian).
                d.writeInt(0xa1b2c3d4.toInt()); d.writeShort(2); d.writeShort(4); d.writeInt(0); d.writeInt(0); d.writeInt(65535 + 5); d.writeInt(147)
                for (r in records) {
                    val raw = r.raw ?: continue
                    val len = raw.size + 5
                    d.writeInt((r.epochMs / 1000).toInt()); d.writeInt(((r.epochMs % 1000) * 1000).toInt()); d.writeInt(len); d.writeInt(len)
                    d.writeByte(if (r.dir == Direction.IN) 0 else 1); d.writeInt(r.id.toInt()); d.write(raw); n++
                }
            }
        }
        return Result(out, n, Files.size(out))
    }
}
