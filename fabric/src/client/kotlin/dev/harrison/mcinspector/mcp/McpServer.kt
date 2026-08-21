package dev.harrison.mcinspector.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Minimal MCP Streamable-HTTP transport on loopback.
 *
 *  POST /mcp    JSON-RPC request or batch -> JSON response (202 for notifications)
 *  GET  /mcp    405 (no server-initiated stream in v1)
 *  DELETE /mcp  200 (session teardown, no-op)
 *  GET  /health {"ok":true,"tools":n}
 */
class McpServer(
    private val protocol: McpProtocol,
    private val toolCount: () -> Int,
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val authToken: String? = null,
) {
    companion object { private val LOGGER = LoggerFactory.getLogger("mcinspector.http") }

    private var server: HttpServer? = null
    private val sessionId = UUID.randomUUID().toString()
    val boundPort: Int get() = server?.address?.port ?: -1

    fun start(): Int {
        val s = HttpServer.create(InetSocketAddress(host, port), 0)
        s.executor = Executors.newCachedThreadPool { r -> Thread(r, "mcinspector-http").apply { isDaemon = true } }
        s.createContext("/mcp", ::handleMcp)
        s.createContext("/health") { ex -> respond(ex, 200, """{"ok":true,"tools":${toolCount()}}""") }
        s.start()
        server = s
        return s.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handleMcp(ex: HttpExchange) {
        try {
            if (!authorized(ex)) return respond(ex, 401, """{"error":"unauthorized"}""")
            when (ex.requestMethod) {
                "POST" -> {
                    val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                    val out = protocol.handle(body)
                    ex.responseHeaders.add("Mcp-Session-Id", sessionId)
                    if (out == null) respond(ex, 202, "") else respond(ex, 200, out)
                }
                "DELETE" -> respond(ex, 200, "")
                else -> respond(ex, 405, """{"error":"method not allowed"}""")
            }
        } catch (t: Throwable) {
            LOGGER.error("[MCInspector] http handler failed", t)
            runCatching { respond(ex, 500, """{"error":"${t.message?.replace('"', '\'')}"}""") }
        }
    }

    private fun authorized(ex: HttpExchange): Boolean {
        val token = authToken ?: return true
        return ex.requestHeaders.getFirst("Authorization") == "Bearer $token"
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        if (bytes.isEmpty()) ex.sendResponseHeaders(status, -1) else ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
    }
}
