package dev.founder.intelligentcore.agent

import dev.founder.intelligentcore.domain.FinanceService
import dev.founder.intelligentcore.domain.PaymentProposal
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.memory.MessageWindowChatMemory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Configuration
class AgentConfig {
    /**
     * Single registration of the finance tools. The agent uses it directly, and the Spring AI MCP
     * server auto-configuration exposes every ToolCallbackProvider bean as MCP tools.
     */
    @Bean
    fun financeToolCallbacks(tools: FinanceTools): ToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(tools).build()

    @Bean
    fun agentChatMemory(): ChatMemory = MessageWindowChatMemory.builder().maxMessages(30).build()
}

data class AgentReply(
    val conversationId: String,
    val reply: String,
    /** Proposals the agent created during this turn, so a UI can offer approve/decline. */
    val proposalsCreated: List<PaymentProposal>
)

class AgentUnavailableException(message: String) : RuntimeException(message)

@Service
class FinanceAgent(
    chatModel: ObjectProvider<ChatModel>,
    private val toolCallbacks: ToolCallbackProvider,
    private val chatMemory: ChatMemory,
    private val finance: FinanceService,
    private val clock: Clock
) {
    // Spring AI 2.0 always registers a ChatClient.Builder, even without a model, so build from the model.
    private val chatClient: ChatClient? = chatModel.ifAvailable
        ?.let { ChatClient.builder(it) }
        ?.defaultToolCallbacks(*toolCallbacks.toolCallbacks)
        ?.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        ?.build()

    val available: Boolean get() = chatClient != null

    fun chat(message: String, conversationId: String?): AgentReply {
        require(message.isNotBlank()) { "message must not be blank" }
        val client = chatClient ?: throw AgentUnavailableException(
            "No chat model is configured. Set ANTHROPIC_API_KEY and restart, or use the REST/MCP endpoints directly."
        )
        val conversation = conversationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val startedAt = Instant.now(clock)

        val reply = client.prompt()
            .system(systemPrompt())
            .user(message)
            .advisors { it.param(ChatMemory.CONVERSATION_ID, conversation) }
            .call()
            .content()
            .orEmpty()

        val created = finance.proposals().filter { it.requestedBy == "ai-tool" && !it.createdAt.isBefore(startedAt) }
        return AgentReply(conversation, reply, created)
    }

    private fun systemPrompt() = """
        You are the finance operations assistant for a small Dutch business. Today is ${LocalDate.now(clock)}.
        Account data comes from the '${finance.dataSource}' data source.

        You can read the balance, read transactions and prepare payment proposals with your tools.
        Rules you must follow:
        - Always use the tools for figures. Never estimate or invent balances, transactions or IBANs.
        - prepare_payment only creates a proposal. It never moves money. Never say or imply that a
          payment was made, sent or scheduled.
        - A proposal with status PENDING_APPROVAL needs a human to approve it outside this chat.
          You cannot approve, decline or execute payments, and you must not offer to.
        - If a proposal comes back REJECTED_POLICY, explain the minimum-balance reason using the
          numbers in the proposal and do not try to work around the policy (for example by
          splitting the payment into smaller ones).
        - Ask for missing essentials (who to pay and how much) before preparing a payment.
          Use a descriptive, unique idempotencyKey per payment and reuse it only for retries.
        - Show amounts as EUR with two decimals. Be concise.
    """.trimIndent()
}
