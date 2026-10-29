package dev.founder.intelligentcore.adapter.truelayer

import dev.founder.intelligentcore.domain.Account
import dev.founder.intelligentcore.domain.AccountDataProvider
import dev.founder.intelligentcore.domain.AccountDataUnavailableException
import dev.founder.intelligentcore.domain.Balance
import dev.founder.intelligentcore.domain.Transaction
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** Reads account, balance and transactions from the TrueLayer Data API (sandbox by default). */
class TrueLayerAccountDataProvider(
    private val client: TrueLayerClient,
    private val connection: TrueLayerConnection,
    private val props: TrueLayerProperties,
    private val clock: Clock
) : AccountDataProvider {

    override val source = "truelayer-sandbox"

    @Volatile private var cachedAccount: Pair<String, Account>? = null

    override fun account(): Account {
        val token = connection.accessToken()
        // Cache per connection so every balance call doesn't re-list accounts.
        cachedAccount?.takeIf { it.first == connection.connectedAt.toString() }?.let { return it.second }

        val accounts = client.results(token, "/accounts")
        val chosen = if (props.accountId.isNullOrBlank()) accounts.firstOrNull()
        else accounts.firstOrNull { it["account_id"] == props.accountId }
        chosen ?: throw AccountDataUnavailableException(
            if (props.accountId.isNullOrBlank()) "The connected bank returned no accounts."
            else "Account ${props.accountId} was not found in the connected bank."
        )

        @Suppress("UNCHECKED_CAST")
        val number = chosen["account_number"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val provider = chosen["provider"] as? Map<String, Any?>
        val account = Account(
            id = chosen["account_id"] as String,
            displayName = (chosen["display_name"] as? String ?: "Bank account") +
                ((provider?.get("display_name") as? String)?.let { " — $it" } ?: ""),
            iban = number?.get("iban") as? String,
            currency = chosen["currency"] as? String ?: "GBP",
            provider = provider?.get("provider_id") as? String ?: "truelayer"
        )
        cachedAccount = connection.connectedAt.toString() to account
        return account
    }

    override fun balance(): Balance {
        val account = account()
        val result = client.results(connection.accessToken(), "/accounts/${account.id}/balance").firstOrNull()
            ?: throw AccountDataUnavailableException("The bank returned no balance for ${account.id}.")
        val current = decimal(result["current"])
        return Balance(
            accountId = account.id,
            available = result["available"]?.let(::decimal) ?: current,
            current = current,
            currency = result["currency"] as? String ?: account.currency,
            asOf = (result["update_timestamp"] as? String)?.let(::instant) ?: Instant.now(clock)
        )
    }

    override fun transactions(from: LocalDate?, to: LocalDate?): List<Transaction> {
        val account = account()
        val start = from ?: LocalDate.now(clock).minusDays(props.defaultHistoryDays)
        val end = to ?: LocalDate.now(clock)
        val rows = client.results(
            connection.accessToken(),
            "/accounts/${account.id}/transactions",
            mapOf("from" to "${start}T00:00:00", "to" to "${end}T23:59:59")
        )
        return rows.map { row ->
            @Suppress("UNCHECKED_CAST")
            val classification = (row["transaction_classification"] as? List<String>).orEmpty()
            Transaction(
                id = row["transaction_id"] as String,
                bookedOn = LocalDate.parse((row["timestamp"] as String).take(10)),
                description = row["description"] as? String ?: "",
                counterparty = row["merchant_name"] as? String,
                amount = decimal(row["amount"]),
                currency = row["currency"] as? String ?: account.currency,
                category = classification.joinToString(" / ").ifBlank { row["transaction_category"] as? String }
            )
        }.filter { !it.bookedOn.isBefore(start) && !it.bookedOn.isAfter(end) }
    }

    private fun decimal(value: Any?): BigDecimal = when (value) {
        is BigDecimal -> value
        is Number -> BigDecimal(value.toString())
        is String -> BigDecimal(value)
        else -> throw IllegalStateException("Expected a number from TrueLayer, got $value")
    }

    private fun instant(value: String): Instant =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse { Instant.now(clock) }
}
