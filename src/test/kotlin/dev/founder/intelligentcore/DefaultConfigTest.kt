package dev.founder.intelligentcore

import dev.founder.intelligentcore.agent.FinanceAgent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** With the shipped configuration and no API key, the app starts and reports the agent as unavailable. */
@DisabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest(properties = ["spring.ai.mcp.server.enabled=false"])
class DefaultConfigTest {
    @Autowired lateinit var agent: FinanceAgent

    @Test
    fun `missing API key means no agent rather than failing requests`() {
        assertFalse(agent.available)
    }
}
