package dev.founder.intelligentcore

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in LLM that plays back a fixed script of turns. Each turn is either a set of tool calls
 * or a final text answer. Spring AI's real tool-calling loop runs around it, so the tools,
 * policy and conversation plumbing are all exercised for real; only the "thinking" is scripted.
 */
class ScriptedChatModel : ChatModel {

    sealed interface Turn
    data class CallTools(val calls: List<Pair<String, String>>) : Turn
    data class Answer(val text: String) : Turn

    private val script = ConcurrentLinkedQueue<Turn>()
    val prompts = CopyOnWriteArrayList<Prompt>()

    fun script(vararg turns: Turn) {
        script.clear()
        prompts.clear()
        script.addAll(turns)
    }

    override fun call(prompt: Prompt): ChatResponse {
        prompts += prompt
        val message = when (val turn = script.poll() ?: error("ScriptedChatModel ran out of turns")) {
            is Answer -> AssistantMessage.builder().content(turn.text).build()
            is CallTools -> AssistantMessage.builder()
                .content("")
                .toolCalls(turn.calls.mapIndexed { i, (name, args) ->
                    AssistantMessage.ToolCall("call_${prompts.size}_$i", "function", name, args)
                })
                .build()
        }
        return ChatResponse(listOf(Generation(message)))
    }

    override fun getDefaultOptions(): ChatOptions = ToolCallingChatOptions.builder().build()
}
