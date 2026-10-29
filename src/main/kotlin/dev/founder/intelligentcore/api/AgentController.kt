package dev.founder.intelligentcore.api

import dev.founder.intelligentcore.agent.FinanceAgent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ChatRequest(val message: String, val conversationId: String? = null)

@RestController
@RequestMapping("/api/agent")
class AgentController(private val agent: FinanceAgent) {

    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest) = agent.chat(request.message, request.conversationId)

    @GetMapping("/status")
    fun status() = mapOf("llmConfigured" to agent.available)
}
