package dev.founder.intelligentcore.domain

import java.time.LocalDate

/**
 * Port for read-only bank data. The synthetic adapter and the TrueLayer sandbox
 * adapter both implement it; the domain and the agent never know which one is active.
 */
interface AccountDataProvider {
    /** Short label shown to users and the LLM, e.g. "synthetic" or "truelayer-sandbox". */
    val source: String

    fun account(): Account

    fun balance(): Balance

    /** Most recent first. */
    fun transactions(from: LocalDate? = null, to: LocalDate? = null): List<Transaction>
}

/** Raised when the active provider can't serve data yet (e.g. bank not connected). */
class AccountDataUnavailableException(message: String) : RuntimeException(message)
