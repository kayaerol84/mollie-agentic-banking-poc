package dev.founder.intelligentcore

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Transaction(
    val id: String,
    val description: String,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val bookedAt: Instant = Instant.now()
)

data class BalanceResponse(
    val availableBalance: BigDecimal,
    val currency: String,
    val minimumRequiredBalance: BigDecimal
)

data class PreparePaymentRequest(
    val beneficiary: String,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val idempotencyKey: String
)

data class PaymentProposal(
    val id: String = UUID.randomUUID().toString(),
    val beneficiary: String,
    val amount: BigDecimal,
    val currency: String,
    val balanceBefore: BigDecimal,
    val projectedBalance: BigDecimal,
    val minimumRequiredBalance: BigDecimal,
    val status: String,
    val reason: String,
    val createdAt: Instant = Instant.now()
)
