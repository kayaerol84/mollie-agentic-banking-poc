package dev.founder.intelligentcore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The REST surface used by the demo page, including the human approval step. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.ai.model.chat=none", "spring.ai.mcp.server.enabled=false"]
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HttpApiTest {

    @Value("\${local.server.port}")
    var port: Int = 0

    private val http = HttpClient.newHttpClient()

    private fun get(path: String) = send(HttpRequest.newBuilder(uri(path)).GET())
    private fun post(path: String, json: String) = send(
        HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json))
    )
    private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")
    private fun send(b: HttpRequest.Builder): HttpResponse<String> = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    private fun HttpResponse<String>.field(name: String) = Regex("\"$name\":\"([^\"]*)\"").find(body())!!.groupValues[1]

    @Test
    fun `serves the demo page`() {
        val page = get("/")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Agentic Banking POC"))
    }

    @Test
    fun `full human-in-the-loop flow over REST`() {
        val created = post(
            "/api/finance/payment-proposals",
            """{"beneficiary":"Example Supplier BV","amount":1000.00,"idempotencyKey":"rest-1","reference":"Invoice 1043"}"""
        )
        assertEquals(200, created.statusCode(), created.body())
        assertEquals("PENDING_APPROVAL", created.field("status"))
        val id = created.field("id")

        assertTrue(get("/api/finance/balance").body().contains("\"reservedForProposals\":1000.00"))

        val approved = post("/api/finance/payment-proposals/$id/approve", """{"approver":"erol"}""")
        assertEquals("APPROVED", approved.field("status"))
        assertEquals("erol", approved.field("decidedBy"))

        val again = post("/api/finance/payment-proposals/$id/decline", """{"approver":"erol"}""")
        assertEquals(409, again.statusCode())

        assertTrue(get("/api/finance/payment-proposals?status=APPROVED").body().contains(id))
    }

    @Test
    fun `bad input and unknown ids map to clear HTTP errors`() {
        assertEquals(400, post("/api/finance/payment-proposals", """{"beneficiary":"X","amount":-1,"idempotencyKey":"bad"}""").statusCode())
        assertEquals(404, get("/api/finance/payment-proposals/prop-missing").statusCode())
        assertEquals(400, get("/api/finance/transactions?from=2026-10-20&to=2026-10-01").statusCode())
    }

    @Test
    fun `agent endpoint explains when no LLM is configured`() {
        assertTrue(get("/api/agent/status").body().contains("\"llmConfigured\":false"))
        val chat = post("/api/agent/chat", """{"message":"What's my balance?"}""")
        assertEquals(503, chat.statusCode())
        assertTrue(chat.body().contains("ANTHROPIC_API_KEY"))
    }
}
