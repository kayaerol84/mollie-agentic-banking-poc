package dev.founder.intelligentcore.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class Account(
    val id: String,
    val displayName: String,
    val iban: String?,
    val currency: String,
    val provider: String
)

data class Balance(
    val accountId: String,
    val available: BigDecimal,
    val current: BigDecimal,
    val currency: String,
    val asOf: Instant
)

data class Transaction(
    val id: String,
    val bookedOn: LocalDate,
    val description: String,
    val counterparty: String?,
    /** Negative for outgoing money, positive for incoming. */
    val amount: BigDecimal,
    val currency: String,
    val category: String?
)

/** What callers (REST, agent tools, MCP) see when they ask for the balance. */
data class BalanceView(
    val accountId: String,
    val accountName: String,
    val availableBalance: BigDecimal,
    val reservedForProposals: BigDecimal,
    val spendableAboveMinimum: BigDecimal,
    val minimumRequiredBalance: BigDecimal,
    val currency: String,
    val asOf: Instant,
    val dataSource: String
)

data class PreparePaymentCommand(
    val beneficiary: String,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val idempotencyKey: String,
    val beneficiaryIban: String? = null,
    val reference: String? = null,
    val requestedBy: String = "api"
)

enum class ProposalStatus {
    /** Passed policy; waiting for a human decision. */
    PENDING_APPROVAL,
    /** Blocked by deterministic policy; can never be approved. */
    REJECTED_POLICY,
    /** A human approved it. In this POC nothing is executed. */
    APPROVED,
    /** A human declined it. */
    DECLINED
}

data class PaymentProposal(
    val id: String,
    val idempotencyKey: String,
    val beneficiary: String,
    val beneficiaryIban: String?,
    val amount: BigDecimal,
    val currency: String,
    val reference: String?,
    val balanceBefore: BigDecimal,
    val reservedBefore: BigDecimal,
    val projectedBalance: BigDecimal,
    val minimumRequiredBalance: BigDecimal,
    val status: ProposalStatus,
    val reason: String,
    val requestedBy: String,
    val createdAt: Instant,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null
)
