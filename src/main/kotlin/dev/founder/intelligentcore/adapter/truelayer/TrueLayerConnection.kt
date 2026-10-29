package dev.founder.intelligentcore.adapter.truelayer

import dev.founder.intelligentcore.domain.AccountDataUnavailableException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the single bank connection for this demo app, in memory. A restart means reconnecting.
 * A multi-user product would store encrypted tokens per user instead.
 */
class TrueLayerConnection(private val client: TrueLayerClient, private val clock: Clock) {

    @Volatile private var tokens: TrueLayerTokens? = null
    @Volatile var connectedAt: Instant? = null
        private set
    private val pendingStates = ConcurrentHashMap<String, Instant>()

    val isConnected: Boolean get() = tokens != null

    fun startAuthorization(): java.net.URI {
        val now = Instant.now(clock)
        pendingStates.entries.removeIf { it.value.isBefore(now.minus(STATE_TTL)) }
        val state = UUID.randomUUID().toString()
        pendingStates[state] = now
        return client.authorizationUrl(state)
    }

    fun completeAuthorization(code: String, state: String) {
        val issued = pendingStates.remove(state)
        require(issued != null && issued.isAfter(Instant.now(clock).minus(STATE_TTL))) {
            "Unknown or expired state; start again at /truelayer/connect"
        }
        tokens = client.exchangeCode(code)
        connectedAt = Instant.now(clock)
    }

    fun disconnect() {
        tokens = null
        connectedAt = null
    }

    /** A valid access token, refreshing it if needed. */
    @Synchronized
    fun accessToken(): String {
        val current = tokens ?: throw AccountDataUnavailableException(
            "No bank is connected yet. Open /truelayer/connect to link a TrueLayer sandbox account."
        )
        if (Instant.now(clock).isBefore(current.expiresAt)) return current.accessToken
        val refreshToken = current.refreshToken ?: run {
            tokens = null
            throw AccountDataUnavailableException("The bank connection expired. Reconnect at /truelayer/connect.")
        }
        val renewed = client.refresh(refreshToken)
        tokens = renewed.copy(refreshToken = renewed.refreshToken ?: refreshToken)
        return renewed.accessToken
    }

    private companion object {
        val STATE_TTL: Duration = Duration.ofMinutes(10)
    }
}
