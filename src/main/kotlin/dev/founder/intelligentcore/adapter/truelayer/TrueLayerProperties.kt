package dev.founder.intelligentcore.adapter.truelayer

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * TrueLayer Data API settings. Defaults point at the sandbox and its Mock Bank
 * (log in with john / doe). Credentials come from TrueLayer Console with "Live" switched off.
 */
@ConfigurationProperties("truelayer")
data class TrueLayerProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    /** Must be registered under Allowed redirect URIs in TrueLayer Console. */
    val redirectUri: String = "http://localhost:8080/truelayer/callback",
    val authBaseUrl: String = "https://auth.truelayer-sandbox.com",
    val apiBaseUrl: String = "https://api.truelayer-sandbox.com",
    /** Space-separated provider ids shown on the auth page. uk-cs-mock is the sandbox Mock Bank. */
    val providers: String = "uk-cs-mock",
    val scopes: String = "info accounts balance transactions offline_access",
    /** Optional: which account to use when the connection has several. Defaults to the first. */
    val accountId: String? = null,
    /** Days of history to fetch when no start date is given. */
    val defaultHistoryDays: Long = 90
)
