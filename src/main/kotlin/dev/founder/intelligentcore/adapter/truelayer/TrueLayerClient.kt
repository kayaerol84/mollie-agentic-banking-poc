package dev.founder.intelligentcore.adapter.truelayer

import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

data class TrueLayerTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant
)

class TrueLayerApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Thin HTTP client for TrueLayer auth and Data API v1. Parses JSON into plain maps on purpose. */
class TrueLayerClient(
    private val props: TrueLayerProperties,
    private val clock: Clock,
    private val http: RestClient = RestClient.create()
) {
    // Money arrives as JSON numbers; keep them exact.
    private val json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    fun authorizationUrl(state: String): URI = UriComponentsBuilder.fromUriString(props.authBaseUrl + "/")
        .queryParam("response_type", "code")
        .queryParam("client_id", props.clientId)
        .queryParam("scope", props.scopes)
        .queryParam("redirect_uri", props.redirectUri)
        .queryParam("providers", props.providers)
        .queryParam("state", state)
        .encode()
        .build()
        .toUri()

    fun exchangeCode(code: String): TrueLayerTokens = token(
        "grant_type" to "authorization_code",
        "client_id" to props.clientId,
        "client_secret" to props.clientSecret,
        "redirect_uri" to props.redirectUri,
        "code" to code
    )

    fun refresh(refreshToken: String): TrueLayerTokens = token(
        "grant_type" to "refresh_token",
        "client_id" to props.clientId,
        "client_secret" to props.clientSecret,
        "refresh_token" to refreshToken
    )

    /** GET a Data API v1 resource and return its `results` array. */
    fun results(accessToken: String, path: String, query: Map<String, String> = emptyMap()): List<Map<String, Any?>> {
        val uri = UriComponentsBuilder.fromUriString(props.apiBaseUrl + "/data/v1" + path)
            .apply { query.forEach { (k, v) -> queryParam(k, v) } }
            .encode().build().toUri()
        val body = call("GET $path") {
            http.get().uri(uri).header("Authorization", "Bearer $accessToken").retrieve().body(String::class.java)
        }
        @Suppress("UNCHECKED_CAST")
        return (parse(body)["results"] as? List<Map<String, Any?>>) ?: emptyList()
    }

    private fun token(vararg fields: Pair<String, String>): TrueLayerTokens {
        val form = fields.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8) }
        val body = call("token request") {
            http.post().uri(props.authBaseUrl + "/connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String::class.java)
        }
        val map = parse(body)
        val accessToken = map["access_token"] as? String ?: throw TrueLayerApiException("Token response had no access_token")
        val expiresIn = (map["expires_in"] as? Number)?.toLong() ?: 3600
        return TrueLayerTokens(
            accessToken = accessToken,
            refreshToken = map["refresh_token"] as? String,
            // Refresh a minute early so a token never expires mid-request.
            expiresAt = Instant.now(clock).plusSeconds((expiresIn - 60).coerceAtLeast(0))
        )
    }

    private fun call(what: String, block: () -> String?): String = try {
        block() ?: throw TrueLayerApiException("Empty response from TrueLayer ($what)")
    } catch (e: RestClientResponseException) {
        throw TrueLayerApiException("TrueLayer $what failed with HTTP ${e.statusCode.value()}: ${e.responseBodyAsString.take(300)}", e)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>
}
