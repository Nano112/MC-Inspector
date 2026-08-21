package dev.harrison.mcinspector.mcp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerTest {
    private val client = HttpClient.newHttpClient()
    private var server: McpServer? = null

    private fun start(token: String? = null): Int {
        val s = McpServer(McpProtocol(ToolRegistry(), "t", "0"), { 0 }, port = 0, authToken = token)
        server = s
        return s.start()
    }

    @AfterTest fun tearDown() { server?.stop() }

    private fun post(port: Int, body: String, vararg headers: String): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
        var i = 0
        while (i < headers.size) { b.header(headers[i], headers[i + 1]); i += 2 }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun pingOverHttp() {
        val port = start()
        val r = post(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"result\":{}"))
        assertTrue(r.headers().firstValue("Mcp-Session-Id").isPresent)
    }

    @Test fun notificationIs202() {
        val port = start()
        assertEquals(202, post(port, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())
    }

    @Test fun health() {
        val port = start()
        val r = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"ok\":true"))
    }

    @Test fun getMcpIs405() {
        val port = start()
        val r = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(405, r.statusCode())
    }

    @Test fun authToken() {
        val port = start("s3")
        assertEquals(401, post(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").statusCode())
        assertEquals(200, post(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", "Authorization", "Bearer s3").statusCode())
    }
}
