package dev.founder.intelligentcore.adapter.truelayer

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Clock

/** Active when `finance.data.provider=truelayer`. Replaces the synthetic adapter. */
@Configuration
@ConditionalOnProperty(name = ["finance.data.provider"], havingValue = "truelayer")
class TrueLayerConfiguration {

    @Bean
    fun trueLayerClient(props: TrueLayerProperties, clock: Clock): TrueLayerClient {
        check(props.clientId.isNotBlank() && props.clientSecret.isNotBlank()) {
            "finance.data.provider=truelayer needs TRUELAYER_CLIENT_ID and TRUELAYER_CLIENT_SECRET " +
                "(TrueLayer Console, sandbox mode)."
        }
        return TrueLayerClient(props, clock)
    }

    @Bean
    fun trueLayerConnection(client: TrueLayerClient, clock: Clock) = TrueLayerConnection(client, clock)

    @Bean
    fun trueLayerAccountDataProvider(
        client: TrueLayerClient,
        connection: TrueLayerConnection,
        props: TrueLayerProperties,
        clock: Clock
    ) = TrueLayerAccountDataProvider(client, connection, props, clock)
}

/** Browser-facing OAuth flow for linking the sandbox bank account. */
@RestController
@RequestMapping("/truelayer")
@ConditionalOnProperty(name = ["finance.data.provider"], havingValue = "truelayer")
class TrueLayerAuthController(private val connection: TrueLayerConnection) {

    @GetMapping("/connect")
    fun connect(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(connection.startAuthorization()).build()

    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?
    ): ResponseEntity<Any> {
        if (error != null) {
            return ResponseEntity.badRequest().body(mapOf("error" to error, "detail" to "Authorisation was not completed."))
        }
        require(!code.isNullOrBlank() && !state.isNullOrBlank()) { "code and state are required" }
        connection.completeAuthorization(code, state)
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/?connected=truelayer")).build()
    }

    @GetMapping("/status")
    fun status() = mapOf("connected" to connection.isConnected, "connectedAt" to connection.connectedAt)

    @PostMapping("/disconnect")
    fun disconnect() = connection.disconnect().let { mapOf("connected" to false) }
}
