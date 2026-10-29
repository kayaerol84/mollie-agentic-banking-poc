package dev.founder.intelligentcore.domain

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic domain and policy layer. Everything that touches money goes through here,
 * whether the caller is a REST client, the LLM agent or an MCP client. The LLM can only
 * prepare proposals; approving or declining is a separate, human-only operation.
 */
@Service
class FinanceService(
    private val accounts: AccountDataProvider,
    @Value("\${finance.policy.minimum-balance:5000.00}") minimumBalance: BigDecimal,
    private val clock: Clock
) {
    private val minimumBalance = minimumBalance.setScale(2, RoundingMode.HALF_UP)
    private val proposalsById = ConcurrentHashMap<String, PaymentProposal>()
    private val proposalIdsByKey = ConcurrentHashMap<String, String>()

    val dataSource: String get() = accounts.source

    fun balance(): BalanceView {
        val account = accounts.account()
        val balance = accounts.balance()
        val reserved = reserved()
        return BalanceView(
            accountId = account.id,
            accountName = account.displayName,
            availableBalance = balance.available.setScale(2, RoundingMode.HALF_UP),
            reservedForProposals = reserved,
            spendableAboveMinimum = (balance.available - reserved - minimumBalance).max(BigDecimal.ZERO.setScale(2)),
            minimumRequiredBalance = minimumBalance,
            currency = balance.currency,
            asOf = balance.asOf,
            dataSource = accounts.source
        )
    }

    fun transactions(from: LocalDate? = null, to: LocalDate? = null, limit: Int? = null): List<Transaction> {
        require(limit == null || limit > 0) { "limit must be positive" }
        if (from != null && to != null) require(!from.isAfter(to)) { "from must not be after to" }
        val all = accounts.transactions(from, to).sortedByDescending { it.bookedOn }
        return if (limit == null) all else all.take(limit)
    }

    @Synchronized
    fun preparePayment(command: PreparePaymentCommand): PaymentProposal {
        require(command.beneficiary.isNotBlank()) { "beneficiary must not be blank" }
        require(command.amount > BigDecimal.ZERO) { "amount must be greater than zero" }
        require(command.amount.scale() <= 2) { "amount must have at most 2 decimals" }
        require(command.idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }

        proposalIdsByKey[command.idempotencyKey]?.let { existingId ->
            val existing = proposalsById.getValue(existingId)
            require(existing.amount.compareTo(command.amount) == 0 && existing.beneficiary == command.beneficiary.trim()) {
                "idempotencyKey '${command.idempotencyKey}' was already used for a different payment"
            }
            return existing
        }

        val balance = accounts.balance()
        // Payments are made in the account's own currency; no FX in this POC.
        val currency = command.currency ?: balance.currency
        require(currency == balance.currency) {
            "this account only pays in ${balance.currency}; got $currency"
        }
        val reserved = reserved()
        val projected = (balance.available - reserved - command.amount).setScale(2, RoundingMode.HALF_UP)
        val allowed = projected >= minimumBalance

        val proposal = PaymentProposal(
            id = "prop-" + UUID.randomUUID().toString().substring(0, 8),
            idempotencyKey = command.idempotencyKey,
            beneficiary = command.beneficiary.trim(),
            beneficiaryIban = command.beneficiaryIban?.replace(" ", "")?.uppercase(),
            amount = command.amount.setScale(2),
            currency = currency,
            reference = command.reference,
            balanceBefore = balance.available.setScale(2, RoundingMode.HALF_UP),
            reservedBefore = reserved,
            projectedBalance = projected,
            minimumRequiredBalance = minimumBalance,
            status = if (allowed) ProposalStatus.PENDING_APPROVAL else ProposalStatus.REJECTED_POLICY,
            reason = if (allowed) {
                "Payment prepared; a human must approve it. No money has moved."
            } else {
                "Payment would leave $projected $currency after existing reservations of $reserved, " +
                    "below the minimum balance of $minimumBalance. No money has moved."
            },
            requestedBy = command.requestedBy,
            createdAt = Instant.now(clock)
        )
        proposalsById[proposal.id] = proposal
        proposalIdsByKey[command.idempotencyKey] = proposal.id
        return proposal
    }

    fun proposals(status: ProposalStatus? = null): List<PaymentProposal> =
        proposalsById.values.filter { status == null || it.status == status }.sortedByDescending { it.createdAt }

    fun proposal(id: String): PaymentProposal =
        proposalsById[id] ?: throw NoSuchElementException("No payment proposal with id $id")

    /** Human-only. Not exposed as an agent or MCP tool. */
    @Synchronized
    fun approve(id: String, approver: String): PaymentProposal = decide(id, approver, ProposalStatus.APPROVED)

    /** Human-only. Not exposed as an agent or MCP tool. */
    @Synchronized
    fun decline(id: String, approver: String): PaymentProposal = decide(id, approver, ProposalStatus.DECLINED)

    private fun decide(id: String, approver: String, outcome: ProposalStatus): PaymentProposal {
        require(approver.isNotBlank()) { "approver must not be blank" }
        val current = proposal(id)
        check(current.status == ProposalStatus.PENDING_APPROVAL) {
            "Proposal $id is ${current.status} and can no longer be decided"
        }
        val updated = current.copy(
            status = outcome,
            reason = if (outcome == ProposalStatus.APPROVED) {
                "Approved by $approver. Execution is not implemented in this POC, so no money has moved."
            } else {
                "Declined by $approver. No money has moved."
            },
            decidedBy = approver,
            decidedAt = Instant.now(clock)
        )
        proposalsById[id] = updated
        return updated
    }

    /** Money already promised to proposals that are pending or approved. */
    private fun reserved(): BigDecimal =
        proposalsById.values
            .filter { it.status == ProposalStatus.PENDING_APPROVAL || it.status == ProposalStatus.APPROVED }
            .fold(BigDecimal.ZERO.setScale(2)) { acc, p -> acc + p.amount }
}
