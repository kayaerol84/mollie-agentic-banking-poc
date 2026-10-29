package dev.founder.intelligentcore

import dev.founder.intelligentcore.adapter.synthetic.SyntheticAccountDataProvider
import dev.founder.intelligentcore.domain.FinanceService
import dev.founder.intelligentcore.domain.PreparePaymentCommand
import dev.founder.intelligentcore.domain.ProposalStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FinanceServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-29T09:00:00Z"), ZoneOffset.UTC)
    private val service = FinanceService(SyntheticAccountDataProvider(clock), BigDecimal("5000.00"), clock)

    private fun pay(amount: String, key: String, beneficiary: String = "Supplier") =
        service.preparePayment(PreparePaymentCommand(beneficiary, BigDecimal(amount), "EUR", key))

    @Test
    fun `rejects proposal that breaches minimum balance`() {
        val result = pay("8000.00", "test-key-1")
        assertEquals(ProposalStatus.REJECTED_POLICY, result.status)
        assertTrue(result.projectedBalance < result.minimumRequiredBalance)
    }

    @Test
    fun `requires approval for allowed proposal and moves no money`() {
        val result = pay("1000.00", "test-key-2")
        assertEquals(ProposalStatus.PENDING_APPROVAL, result.status)
        assertEquals(BigDecimal("12500.00"), service.balance().availableBalance)
    }

    @Test
    fun `same idempotency key returns same proposal`() {
        val first = pay("500.00", "same-key")
        val second = pay("500.00", "same-key")
        assertEquals(first.id, second.id)
        assertEquals(1, service.proposals().size)
    }

    @Test
    fun `reusing an idempotency key for a different payment is refused`() {
        pay("500.00", "reused-key")
        assertThrows<IllegalArgumentException> { pay("900.00", "reused-key") }
    }

    @Test
    fun `pending proposals reserve funds so the policy cannot be bypassed by splitting`() {
        // 12,500 available, 5,000 minimum: 7,500 spendable in total.
        assertEquals(ProposalStatus.PENDING_APPROVAL, pay("4000.00", "split-1").status)
        val second = pay("4000.00", "split-2")
        assertEquals(ProposalStatus.REJECTED_POLICY, second.status)
        assertEquals(BigDecimal("4000.00"), second.reservedBefore)
        assertEquals(BigDecimal("3500.00"), service.balance().spendableAboveMinimum)
    }

    @Test
    fun `declining a proposal releases its reservation`() {
        val first = pay("4000.00", "release-1")
        service.decline(first.id, "erol")
        assertEquals(ProposalStatus.PENDING_APPROVAL, pay("4000.00", "release-2").status)
    }

    @Test
    fun `approval is recorded but moves no money`() {
        val proposal = pay("1000.00", "approve-1")
        val approved = service.approve(proposal.id, "erol")
        assertEquals(ProposalStatus.APPROVED, approved.status)
        assertEquals("erol", approved.decidedBy)
        assertEquals(BigDecimal("12500.00"), service.balance().availableBalance)
    }

    @Test
    fun `policy rejections can never be approved`() {
        val rejected = pay("8000.00", "never-1")
        assertThrows<IllegalStateException> { service.approve(rejected.id, "erol") }
    }

    @Test
    fun `validates input`() {
        assertThrows<IllegalArgumentException> { pay("0", "v-1") }
        assertThrows<IllegalArgumentException> { pay("10.001", "v-2") }
        assertThrows<IllegalArgumentException> { pay("10.00", "v-3", beneficiary = " ") }
        assertThrows<IllegalArgumentException> {
            service.preparePayment(PreparePaymentCommand("Supplier", BigDecimal.TEN, "USD", "v-4"))
        }
    }

    @Test
    fun `filters transactions by date and limit, most recent first`() {
        val october = service.transactions(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-15"))
        assertTrue(october.all { it.bookedOn.monthValue == 10 && it.bookedOn.dayOfMonth <= 15 })
        assertEquals(october.sortedByDescending { it.bookedOn }, october)
        assertEquals(3, service.transactions(limit = 3).size)
    }
}
