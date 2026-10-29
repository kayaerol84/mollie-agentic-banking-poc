package dev.founder.intelligentcore

import dev.founder.intelligentcore.agent.FinanceTools
import dev.founder.intelligentcore.domain.FinanceService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * The TrueLayer adapter replacing the synthetic one: OAuth connect flow, token refresh,
 * and Data API mapping, all behind the same port the agent and MCP tools use.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.ai.model.chat=none",
        "spring.ai.mcp.server.enabled=false",
        "finance.data.provider=truelayer",
        "finance.policy.minimum-balance=5000.00",
        "truelayer.client-id=${FakeTrueLayer.CLIENT_ID}",
        "truelayer.client-secret=${FakeTrueLayer.CLIENT_SECRET}",
        "truelayer.redirect-uri=http://localhost:8080/truelayer/callback"
    ]
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TrueLayerAdapterTest {

    companion object {
        private val fake = FakeTrueLayer()

        @JvmStatic
        @DynamicPropertySource
        fun trueLayerUrls(registry: DynamicPropertyRegistry) {
            registry.add("truelayer.auth-base-url") { fake.baseUrl }
            registry.add("truelayer.api-base-url") { fake.baseUrl }
        }

        @JvmStatic
        @AfterAll
        fun stop() = fake.close()
    }

    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var finance: FinanceService
    @Autowired lateinit var tools: FinanceTools

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun query(uri: String) = URI.create(uri).rawQuery.split("&")
        .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8) }

    /** Runs the browser part of the OAuth flow the way TrueLayer would. */
    private fun connect() {
        val start = get("/truelayer/connect")
        assertEquals(302, start.statusCode())
        val state = query(start.headers().firstValue("Location").get())["state"]!!
        val callback = get("/truelayer/callback?code=${FakeTrueLayer.GOOD_CODE}&state=$state")
        assertEquals(302, callback.statusCode(), callback.body())
    }

    @Test
    fun `asks the user to connect a bank before any data is available`() {
        val balance = get("/api/finance/balance")
        assertEquals(503, balance.statusCode())
        assertTrue(balance.body().contains("/truelayer/connect"), balance.body())
        assertTrue(get("/truelayer/status").body().contains("\"connected\":false"))
    }

    @Test
    fun `builds a sandbox auth link with the mock bank and offline access`() {
        val location = get("/truelayer/connect").headers().firstValue("Location").get()
        assertTrue(location.startsWith(fake.baseUrl + "/?"), location)
        val q = query(location)
        assertEquals("code", q["response_type"])
        assertEquals(FakeTrueLayer.CLIENT_ID, q["client_id"])
        assertEquals("info accounts balance transactions offline_access", q["scope"])
        assertEquals("uk-cs-mock", q["providers"])
        assertEquals("http://localhost:8080/truelayer/callback", q["redirect_uri"])
        assertTrue(!q["state"].isNullOrBlank())
    }

    @Test
    fun `rejects a callback with an unknown state`() {
        get("/truelayer/connect")
        assertEquals(400, get("/truelayer/callback?code=${FakeTrueLayer.GOOD_CODE}&state=forged").statusCode())
        assertTrue(get("/truelayer/status").body().contains("\"connected\":false"))
    }

    @Test
    fun `after connecting, balance and transactions come from TrueLayer`() {
        connect()
        val exchange = fake.tokenRequests.last()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("http://localhost:8080/truelayer/callback", exchange["redirect_uri"])

        val balance = finance.balance()
        assertEquals("truelayer-sandbox", balance.dataSource)
        assertEquals("GBP", balance.currency)
        assertEquals(BigDecimal("9150.80"), balance.availableBalance)
        assertEquals("TRANSACTION ACCOUNT 1 — MOCK", balance.accountName)

        val txs = finance.transactions()
        assertEquals(listOf("tl-1", "tl-2"), txs.map { it.id })
        assertEquals(BigDecimal("-2.99"), txs[0].amount)
        assertEquals("Google play", txs[0].counterparty)
        assertEquals("Entertainment / Games", txs[0].category)
        assertEquals("CREDIT", txs[1].category)
        assertTrue(fake.dataRequests.any { it.contains("/transactions?from=") && it.contains("[Bearer access-") })
    }

    @Test
    fun `agent tools run unchanged on TrueLayer data, in the account currency`() {
        connect()
        val ok = tools.preparePayment("Example Supplier Ltd", BigDecimal("1000.00"), "tl-pay-1", null, "INV-1043")
        assertEquals("PENDING_APPROVAL", ok.status.name)
        assertEquals("GBP", ok.currency)
        val blocked = tools.preparePayment("Big Vendor Ltd", BigDecimal("4000.00"), "tl-pay-2", null, null)
        assertEquals("REJECTED_POLICY", blocked.status.name)
    }

    @Test
    fun `refreshes an expired access token transparently`() {
        fake.nextExpiresIn = 0 // token is immediately due for refresh
        connect()
        val first = fake.lastIssued
        fake.nextExpiresIn = 3600
        finance.balance()
        val refresh = fake.tokenRequests.last()
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("refresh-$first", refresh["refresh_token"])
        assertTrue(fake.dataRequests.last().contains("[Bearer access-${first + 1}]"), fake.dataRequests.last())
    }
}
