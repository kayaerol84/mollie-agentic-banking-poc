package dev.founder.intelligentcore.adapter.synthetic

import dev.founder.intelligentcore.domain.Account
import dev.founder.intelligentcore.domain.AccountDataProvider
import dev.founder.intelligentcore.domain.Balance
import dev.founder.intelligentcore.domain.Transaction
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * In-memory, entirely made-up data for a small Dutch business account.
 * Active unless `finance.data.provider` is set to something else.
 */
@Component
@ConditionalOnProperty(name = ["finance.data.provider"], havingValue = "synthetic", matchIfMissing = true)
class SyntheticAccountDataProvider(private val clock: Clock) : AccountDataProvider {

    override val source = "synthetic"

    private val account = Account(
        id = "acc-synthetic-001",
        displayName = "Synthetic Studio BV — business current account",
        iban = "NL00SYNT0000000001",
        currency = "EUR",
        provider = "synthetic"
    )

    private val available = BigDecimal("12500.00")

    // Dates are relative to today so "last week" always has data.
    private val transactions get() = listOf(
        tx("txn-014", 2, "Invoice 2026-118", "Northwind Retail BV", "4800.00", "revenue"),
        tx("txn-013", 3, "Cloud hosting — monthly", "Hetzner Online GmbH", "-212.40", "software"),
        tx("txn-012", 5, "Supplier invoice 1042", "Example Supplier BV", "-950.00", "suppliers"),
        tx("txn-011", 6, "Card payment — team lunch", "De Pijp Kitchen", "-86.50", "meals"),
        tx("txn-010", 8, "Payroll — monthly", "Payroll run", "-6400.00", "payroll"),
        tx("txn-009", 9, "Card settlement payout", "Mollie B.V.", "3375.20", "revenue"),
        tx("txn-008", 14, "Software subscriptions", "GitHub, Inc.", "-84.00", "software"),
        tx("txn-007", 15, "Supplier invoice 1039", "Example Supplier BV", "-1950.00", "suppliers"),
        tx("txn-006", 19, "Invoice 2026-112", "Contoso Media BV", "8200.00", "revenue"),
        tx("txn-005", 22, "VAT Q3 2026", "Belastingdienst", "-3120.00", "tax"),
        tx("txn-004", 24, "Card payment — office supplies", "Office Centre Amsterdam", "-142.35", "office"),
        tx("txn-003", 26, "Card settlement payout", "Mollie B.V.", "2910.75", "revenue"),
        tx("txn-002", 28, "Office rent — monthly", "Canal House Offices BV", "-1800.00", "rent"),
        tx("txn-001", 29, "Business insurance Q4", "Centraal Verzekeren NV", "-420.00", "insurance")
    )

    override fun account() = account

    override fun balance() = Balance(
        accountId = account.id,
        available = available,
        current = available,
        currency = "EUR",
        asOf = Instant.now(clock)
    )

    override fun transactions(from: LocalDate?, to: LocalDate?) =
        transactions.filter { (from == null || !it.bookedOn.isBefore(from)) && (to == null || !it.bookedOn.isAfter(to)) }

    private fun tx(id: String, daysAgo: Long, description: String, counterparty: String, amount: String, category: String) =
        Transaction(id, LocalDate.now(clock).minusDays(daysAgo), description, counterparty, BigDecimal(amount), "EUR", category)
}
