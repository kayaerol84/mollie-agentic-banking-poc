package dev.founder.intelligentcore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FinanceServiceTest {
    private val service = FinanceService()

    @Test
    fun `rejects proposal that breaches minimum balance`() {
        val result = service.preparePayment(
            PreparePaymentRequest("Supplier A", BigDecimal("8000.00"), "EUR", "test-key-1")
        )
        assertEquals("REJECTED_POLICY", result.status)
        assertTrue(result.projectedBalance < result.minimumRequiredBalance)
    }

    @Test
    fun `requires approval for allowed proposal and moves no money`() {
        val result = service.preparePayment(
            PreparePaymentRequest("Supplier B", BigDecimal("1000.00"), "EUR", "test-key-2")
        )
        assertEquals("PENDING_APPROVAL", result.status)
        assertEquals(BigDecimal("12500.00"), service.balance().availableBalance)
    }

    @Test
    fun `same idempotency key returns same proposal`() {
        val request = PreparePaymentRequest("Supplier C", BigDecimal("500.00"), "EUR", "same-key")
        val first = service.preparePayment(request)
        val second = service.preparePayment(request)
        assertEquals(first.id, second.id)
    }
}
