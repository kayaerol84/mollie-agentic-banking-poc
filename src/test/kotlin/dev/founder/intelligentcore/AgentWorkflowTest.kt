package dev.founder.intelligentcore

import dev.founder.intelligentcore.ScriptedChatModel.Answer
import dev.founder.intelligentcore.ScriptedChatModel.CallTools
import dev.founder.intelligentcore.agent.FinanceAgent
import dev.founder.intelligentcore.domain.FinanceService
import dev.founder.intelligentcore.domain.ProposalStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.annotation.DirtiesContext
import java.math.BigDecimal

/**
 * End-to-end agent workflow on synthetic data with a scripted LLM: the Spring AI tool loop,
 * tool schemas, chat memory and the policy all run for real.
 */
@SpringBootTest(properties = ["spring.ai.model.chat=none", "spring.ai.mcp.server.enabled=false"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AgentWorkflowTest {

    @TestConfiguration
    class Config {
        @Bean
        fun scriptedChatModel() = ScriptedChatModel()
    }

    @Autowired lateinit var model: ScriptedChatModel
    @Autowired lateinit var agent: FinanceAgent
    @Autowired lateinit var finance: FinanceService

    private fun toolResponses() = model.prompts.last().instructions
        .filter { it.messageType == MessageType.TOOL }
        .flatMap { (it as ToolResponseMessage).responses }

    @Test
    fun `agent checks the balance, prepares a payment and leaves approval to a human`() {
        model.script(
            CallTools(listOf("get_balance" to "{}")),
            CallTools(
                listOf(
                    "prepare_payment" to """{"beneficiary":"Example Supplier BV","amount":1000.00,""" +
                        """"idempotencyKey":"pay-example-supplier-inv-1043","reference":"Invoice 1043"}"""
                )
            ),
            Answer("I prepared a EUR 1,000.00 payment to Example Supplier BV. It is waiting for your approval.")
        )

        val reply = agent.chat("Pay Example Supplier BV €1,000 for invoice 1043", null)

        assertTrue(reply.reply.contains("waiting for your approval"))
        val proposal = reply.proposalsCreated.single()
        assertEquals(ProposalStatus.PENDING_APPROVAL, proposal.status)
        assertEquals(BigDecimal("1000.00"), proposal.amount)
        assertEquals("Invoice 1043", proposal.reference)
        // Nothing moved; only reserved.
        assertEquals(BigDecimal("12500.00"), finance.balance().availableBalance)
        assertEquals(BigDecimal("1000.00"), finance.balance().reservedForProposals)

        // The model was offered exactly the three tools...
        val offered = (model.prompts.first().options as ToolCallingChatOptions).toolCallbacks.map { it.toolDefinition.name() }
        assertEquals(setOf("get_balance", "list_transactions", "prepare_payment"), offered.toSet())
        // ...and saw the real tool output before answering.
        assertTrue(toolResponses().any { it.name() == "prepare_payment" && it.responseData().contains("PENDING_APPROVAL") })
        assertEquals(3, model.prompts.size)
    }

    @Test
    fun `policy rejection reaches the model so it can explain it`() {
        model.script(
            CallTools(listOf("prepare_payment" to """{"beneficiary":"Big Vendor BV","amount":8000.00,"idempotencyKey":"pay-big-vendor-1"}""")),
            Answer("I can't prepare that: it would take the balance below the EUR 5,000.00 minimum.")
        )

        val reply = agent.chat("Pay Big Vendor BV 8000 euro", null)

        assertEquals(ProposalStatus.REJECTED_POLICY, reply.proposalsCreated.single().status)
        assertTrue(toolResponses().single().responseData().contains("REJECTED_POLICY"))
    }

    @Test
    fun `invalid tool arguments are returned to the model instead of failing the request`() {
        model.script(
            CallTools(listOf("prepare_payment" to """{"beneficiary":"Someone","amount":-5,"idempotencyKey":"neg-1"}""")),
            Answer("The amount must be positive. How much should I pay?")
        )

        val reply = agent.chat("Pay Someone minus 5", null)

        assertTrue(reply.proposalsCreated.isEmpty())
        assertTrue(toolResponses().single().responseData().contains("amount must be greater than zero"))
    }

    @Test
    fun `conversation memory carries context across turns`() {
        model.script(Answer("Your available balance is EUR 12,500.00."), Answer("Noted."))

        val first = agent.chat("What's my balance?", null)
        agent.chat("Thanks", first.conversationId)

        val history = model.prompts.last().instructions.filter { it.messageType != MessageType.SYSTEM }.map { it.text }
        assertTrue(history.contains("What's my balance?"), history.toString())
        assertTrue(history.contains("Your available balance is EUR 12,500.00."), history.toString())
    }
}
