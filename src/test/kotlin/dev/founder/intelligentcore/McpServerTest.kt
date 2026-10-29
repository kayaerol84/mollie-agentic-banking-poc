package dev.founder.intelligentcore

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration

/** Connects a real MCP client to the running server over Streamable HTTP. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.ai.model.chat=none"]
)
class McpServerTest {

    @Value("\${local.server.port}")
    var port: Int = 0

    private lateinit var client: McpSyncClient

    @BeforeEach
    fun connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port").build())
            .requestTimeout(Duration.ofSeconds(10))
            .build()
        client.initialize()
    }

    @AfterEach
    fun close() = client.closeGracefully().let { }

    private fun call(name: String, args: Map<String, Any> = emptyMap()): McpSchema.CallToolResult =
        client.callTool(McpSchema.CallToolRequest(name, args))

    private fun McpSchema.CallToolResult.text() =
        content().filterIsInstance<McpSchema.TextContent>().joinToString { it.text() }

    @Test
    fun `lists exactly the finance tools`() {
        val names = client.listTools().tools().map { it.name() }.toSet()
        assertEquals(setOf("get_balance", "list_transactions", "prepare_payment"), names)
    }

    @Test
    fun `get_balance over MCP`() {
        val result = call("get_balance")
        assertFalse(result.isError() == true)
        assertTrue(result.text().contains("12500.00"), result.text())
    }

    @Test
    fun `prepare_payment over MCP enforces the same policy`() {
        val ok = call("prepare_payment", mapOf("beneficiary" to "Example Supplier BV", "amount" to 250.00, "idempotencyKey" to "mcp-1"))
        assertTrue(ok.text().contains("PENDING_APPROVAL"), ok.text())

        val blocked = call("prepare_payment", mapOf("beneficiary" to "Big Vendor BV", "amount" to 9000, "idempotencyKey" to "mcp-2"))
        assertTrue(blocked.text().contains("REJECTED_POLICY"), blocked.text())
    }
}
