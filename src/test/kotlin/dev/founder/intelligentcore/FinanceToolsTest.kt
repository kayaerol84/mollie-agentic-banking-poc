package dev.founder.intelligentcore

import dev.founder.intelligentcore.adapter.synthetic.SyntheticAccountDataProvider
import dev.founder.intelligentcore.agent.FinanceTools
import dev.founder.intelligentcore.domain.FinanceService
import dev.founder.intelligentcore.domain.ProposalStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Exercises the tools exactly as Spring AI will: via generated schemas and JSON arguments. */
class FinanceToolsTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-29T09:00:00Z"), ZoneOffset.UTC)
    private val service = FinanceService(SyntheticAccountDataProvider(clock), BigDecimal("5000.00"), "EUR", clock)
    private val callbacks: Map<String, ToolCallback> = MethodToolCallbackProvider.builder()
        .toolObjects(FinanceTools(service))
        .build()
        .toolCallbacks
        .associateBy { it.toolDefinition.name() }

    @Test
    fun `exposes exactly the three agreed tools and nothing that can approve or execute`() {
        assertEquals(setOf("get_balance", "list_transactions", "prepare_payment"), callbacks.keys)
    }

    @Test
    fun `prepare_payment schema marks only the essentials as required`() {
        val schema = callbacks.getValue("prepare_payment").toolDefinition.inputSchema()
        val required = Regex("\"required\"\\s*:\\s*\\[([^]]*)]").find(schema)!!.groupValues[1]
        assertEquals(setOf("beneficiary", "amount", "idempotencyKey"), Regex("\"(\\w+)\"").findAll(required).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun `get_balance returns policy-aware balance`() {
        val json = callbacks.getValue("get_balance").call("{}")
        assertTrue(json.contains("\"availableBalance\":12500.00"), json)
        assertTrue(json.contains("\"spendableAboveMinimum\":7500.00"), json)
        assertTrue(json.contains("\"dataSource\":\"synthetic\""), json)
    }

    @Test
    fun `list_transactions honours filters`() {
        val json = callbacks.getValue("list_transactions").call("""{"from":"2026-10-20","limit":2}""")
        assertEquals(2, Regex("\"id\":\"txn-").findAll(json).count(), json)
        assertTrue(json.contains("txn-014"), json)
    }

    @Test
    fun `list_transactions explains bad dates to the model instead of crashing`() {
        val result = runCatching { callbacks.getValue("list_transactions").call("""{"from":"last week"}""") }
        val text = result.getOrNull() ?: result.exceptionOrNull()!!.let { generateSequence(it) { e -> e.cause }.joinToString { e -> e.message.orEmpty() } }
        assertTrue(text.contains("ISO date"), text)
    }

    @Test
    fun `prepare_payment creates a pending proposal and never moves money`() {
        val json = callbacks.getValue("prepare_payment").call(
            """{"beneficiary":"Example Supplier BV","amount":1000.00,"idempotencyKey":"inv-1043","reference":"Invoice 1043"}"""
        )
        assertTrue(json.contains("\"status\":\"PENDING_APPROVAL\""), json)
        assertEquals(ProposalStatus.PENDING_APPROVAL, service.proposals().single().status)
        assertEquals("ai-tool", service.proposals().single().requestedBy)
        assertEquals(BigDecimal("12500.00"), service.balance().availableBalance)
    }
}
