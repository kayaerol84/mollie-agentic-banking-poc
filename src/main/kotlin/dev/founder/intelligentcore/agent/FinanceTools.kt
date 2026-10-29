package dev.founder.intelligentcore.agent

import dev.founder.intelligentcore.domain.BalanceView
import dev.founder.intelligentcore.domain.FinanceService
import dev.founder.intelligentcore.domain.PaymentProposal
import dev.founder.intelligentcore.domain.PreparePaymentCommand
import dev.founder.intelligentcore.domain.Transaction
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The only capabilities the LLM (and any MCP client) gets. Two reads and one write that can
 * only create a proposal. There is deliberately no tool to approve, decline or execute.
 *
 * The same object backs the in-app agent (ChatClient) and the MCP server, so both see
 * identical names, descriptions and policy behaviour.
 */
@Component
class FinanceTools(private val finance: FinanceService) {

    @Tool(
        name = "get_balance",
        description = "Get the business account's available balance, the amount already reserved by " +
            "payment proposals, the minimum balance the policy requires, and how much can still be spent " +
            "above that minimum, all in the account currency."
    )
    fun getBalance(): BalanceView = finance.balance()

    @Tool(
        name = "list_transactions",
        description = "List booked transactions on the business account, most recent first. Negative amounts " +
            "are outgoing payments, positive amounts are incoming. Optionally filter by booking date range " +
            "(ISO dates, inclusive) and limit the number of results."
    )
    fun listTransactions(
        @ToolParam(description = "Earliest booking date to include, ISO format YYYY-MM-DD", required = false)
        from: String?,
        @ToolParam(description = "Latest booking date to include, ISO format YYYY-MM-DD", required = false)
        to: String?,
        @ToolParam(description = "Maximum number of transactions to return (1-100). Defaults to 20.", required = false)
        limit: Int?
    ): List<Transaction> {
        val capped = (limit ?: 20).coerceIn(1, 100)
        return finance.transactions(parseDate("from", from), parseDate("to", to), capped)
    }

    @Tool(
        name = "prepare_payment",
        description = "Prepare an outgoing payment proposal in the account currency. This NEVER moves money: it runs the " +
            "deterministic minimum-balance policy and returns a proposal that is either PENDING_APPROVAL " +
            "(a human must approve it outside this conversation) or REJECTED_POLICY. Reuse the same " +
            "idempotencyKey when retrying the same payment so no duplicate proposal is created."
    )
    fun preparePayment(
        @ToolParam(description = "Name of the person or company to pay")
        beneficiary: String,
        @ToolParam(description = "Amount in the account currency, as a decimal number with at most 2 decimals, e.g. 1250.00")
        amount: BigDecimal,
        @ToolParam(
            description = "Unique key for this payment, e.g. 'pay-example-supplier-inv-1043'. " +
                "Reuse it only when retrying the exact same payment."
        )
        idempotencyKey: String,
        @ToolParam(description = "Beneficiary IBAN, if known", required = false)
        beneficiaryIban: String?,
        @ToolParam(description = "Payment reference shown to the beneficiary, e.g. an invoice number", required = false)
        reference: String?
    ): PaymentProposal = finance.preparePayment(
        PreparePaymentCommand(
            beneficiary = beneficiary,
            amount = amount,
            currency = null,
            idempotencyKey = idempotencyKey,
            beneficiaryIban = beneficiaryIban,
            reference = reference,
            requestedBy = "ai-tool"
        )
    )

    private fun parseDate(name: String, value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDate.parse(value.trim())
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("$name must be an ISO date (YYYY-MM-DD), got '$value'")
        }
    }
}
