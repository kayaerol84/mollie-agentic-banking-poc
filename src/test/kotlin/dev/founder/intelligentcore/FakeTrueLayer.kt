package dev.founder.intelligentcore

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny stand-in for TrueLayer's sandbox auth server and Data API v1, serving responses shaped
 * like TrueLayer's documented examples. Lets the adapter be tested end to end without network access.
 */
class FakeTrueLayer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    val dataRequests = CopyOnWriteArrayList<String>()
    private val tokenCounter = AtomicInteger()
    /** Sequence number of the most recently issued token pair (access-N / refresh-N). */
    val lastIssued: Int get() = tokenCounter.get()

    /** Seconds the next issued access token is valid for. */
    @Volatile var nextExpiresIn = 3600

    init {
        server.createContext("/connect/token", ::token)
        server.createContext("/data/v1/", ::data)
        server.start()
    }

    private fun token(ex: HttpExchange) {
        val form = ex.requestBody.readAllBytes().toString(StandardCharsets.UTF_8).split("&").filter { it.isNotBlank() }
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8) }
        tokenRequests += form
        val ok = form["client_id"] == CLIENT_ID && form["client_secret"] == CLIENT_SECRET &&
            when (form["grant_type"]) {
                "authorization_code" -> form["code"] == GOOD_CODE
                "refresh_token" -> form["refresh_token"]?.startsWith("refresh-") == true
                else -> false
            }
        if (!ok) return respond(ex, 400, """{"error":"invalid_grant"}""")
        val n = tokenCounter.incrementAndGet()
        respond(
            ex, 200,
            """{"access_token":"access-$n","expires_in":$nextExpiresIn,"token_type":"Bearer","refresh_token":"refresh-$n","scope":"info accounts balance transactions offline_access"}"""
        )
    }

    private fun data(ex: HttpExchange) {
        val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
        dataRequests += "${ex.requestURI} [$auth]"
        if (!auth.startsWith("Bearer access-")) return respond(ex, 401, """{"error":"unauthorized"}""")
        when (ex.requestURI.path) {
            "/data/v1/accounts" -> respond(ex, 200, ACCOUNTS)
            "/data/v1/accounts/acc-mock-1/balance" -> respond(ex, 200, BALANCE)
            "/data/v1/accounts/acc-mock-1/transactions" -> respond(ex, 200, TRANSACTIONS)
            else -> respond(ex, 404, """{"error":"not_found"}""")
        }
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    companion object {
        const val CLIENT_ID = "sandbox-test-client"
        const val CLIENT_SECRET = "sandbox-test-secret"
        const val GOOD_CODE = "good-code"

        private const val ACCOUNTS = """{"results":[{"update_timestamp":"2026-10-29T08:00:00Z","account_id":"acc-mock-1",
            "account_type":"TRANSACTION","display_name":"TRANSACTION ACCOUNT 1","currency":"GBP",
            "account_number":{"iban":"GB35CLRB04066800003435","number":"10000000","sort_code":"01-21-31"},
            "provider":{"display_name":"MOCK","provider_id":"mock","logo_uri":"https://truelayer.com/img/mock.svg"}}]}"""

        private const val BALANCE = """{"results":[{"currency":"GBP","available":9150.8,"current":8161.2,"overdraft":1000,
            "update_timestamp":"2026-10-29T08:00:00.001222Z"}]}"""

        private const val TRANSACTIONS = """{"results":[
            {"transaction_id":"tl-1","timestamp":"2026-10-27T00:00:00+00:00","description":"GOOGLE PLAY STORE","amount":-2.99,
             "currency":"GBP","transaction_type":"DEBIT","transaction_category":"PURCHASE",
             "transaction_classification":["Entertainment","Games"],"merchant_name":"Google play",
             "running_balance":{"amount":1238.60,"currency":"GBP"},"meta":{"bank_transaction_id":"9882ks-00js","provider_category":"DEB"}},
            {"transaction_id":"tl-2","timestamp":"2026-10-20T00:00:00+00:00","description":"SALARY","amount":2500.00,
             "currency":"GBP","transaction_type":"CREDIT","transaction_category":"CREDIT","transaction_classification":[]}]}"""
    }
}
