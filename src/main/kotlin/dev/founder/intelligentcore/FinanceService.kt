package dev.founder.intelligentcore

import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Service
class FinanceService {
    private val minimumBalance = BigDecimal("5000.00")
    // Synthetic data only. Replace this adapter with a sandbox-backed implementation later.
    private var availableBalance = BigDecimal("12500.00")
    private val transactions = listOf(
        Transaction("txn-001", "Customer settlement", BigDecimal("8200.00")),
        Transaction("txn-002", "Office rent", BigDecimal("-1800.00")),
        Transaction("txn-003", "Cloud services", BigDecimal("-420.00")),
        Transaction("txn-004", "Supplier invoice 1042", BigDecimal("-950.00"))
    )
    private val proposalsByIdempotencyKey = ConcurrentHashMap<String, PaymentProposal>()

    @Synchronized
    fun balance() = BalanceResponse(availableBalance, "EUR", minimumBalance)

    fun transactions() = transactions

    @Synchronized
    fun preparePayment(request: PreparePaymentRequest): PaymentProposal {
        require(request.beneficiary.isNotBlank()) { "beneficiary must not be blank" }
        require(request.amount > BigDecimal.ZERO) { "amount must be greater than zero" }
        require(request.currency == "EUR") { "POC currently supports EUR only" }
        require(request.idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }

        proposalsByIdempotencyKey[request.idempotencyKey]?.let { return it }

        val projected = availableBalance.subtract(request.amount)
        val allowed = projected >= minimumBalance
        val proposal = PaymentProposal(
            beneficiary = request.beneficiary,
            amount = request.amount,
            currency = request.currency,
            balanceBefore = availableBalance,
            projectedBalance = projected,
            minimumRequiredBalance = minimumBalance,
            status = if (allowed) "PENDING_APPROVAL" else "REJECTED_POLICY",
            reason = if (allowed) {
                "Payment prepared; explicit approval is required. No money has moved."
            } else {
                "Payment would breach the minimum balance policy. No money has moved."
            }
        )
        proposalsByIdempotencyKey[request.idempotencyKey] = proposal
        return proposal
    }
}
