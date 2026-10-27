package com.sabihon.todo.data.ai

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.DraftSource
import com.sabihon.todo.domain.model.ai.TaskDraft
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AiGateway] backed by Firebase Callable Functions.
 *
 * Why callables: the SDK attaches the user's ID token (and App Check token) to every
 * call, so the function can enforce auth, per-user rate limits and token budgets
 * without the app ever holding a provider API key. The APK only ever talks to *our*
 * gateway – the "proxy key" idea from the video.
 */
@Singleton
class CallableAiGateway @Inject constructor(
    private val functions: FirebaseFunctions
) : AiGateway {

    override suspend fun parseTask(text: String, context: AiContext): TaskDraft {
        val payload = hashMapOf<String, Any?>(
            "text" to text,
            "timeZone" to context.zoneId,
            "nowMillis" to context.nowMillis,
            "categories" to context.categories.map { mapOf("id" to it.id, "name" to it.name) }
        )
        val data = call(AiConfig.FN_PARSE_TASK, payload)
        return data.toTaskDraft()
    }

    override suspend fun suggestSubTasks(title: String, description: String): List<String> {
        val payload = hashMapOf<String, Any?>(
            "title" to title,
            "description" to description
        )
        val data = call(AiConfig.FN_SUGGEST_SUBTASKS, payload)
        return data.stringList("subTasks")
    }

    private suspend fun call(name: String, payload: Map<String, Any?>): Map<*, *> {
        try {
            val result = functions.getHttpsCallable(name).call(payload).await()
            return result.data as? Map<*, *>
                ?: throw AiGatewayException(AiFailure.UNKNOWN, "Unexpected response from $name")
        } catch (e: AiGatewayException) {
            throw e
        } catch (e: FirebaseFunctionsException) {
            throw AiGatewayException(mapCode(e), e.message, e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiGatewayException(AiFailure.UNAVAILABLE, e.message, e)
        }
    }

    private fun mapCode(e: FirebaseFunctionsException): AiFailure {
        val reason = (e.details as? Map<*, *>)?.get("reason")?.toString()
        return when (e.code) {
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                if (reason == "BUDGET_EXCEEDED") AiFailure.BUDGET_EXCEEDED else AiFailure.RATE_LIMITED
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.PERMISSION_DENIED -> AiFailure.UNAUTHENTICATED
            FirebaseFunctionsException.Code.INVALID_ARGUMENT -> AiFailure.INVALID_INPUT
            FirebaseFunctionsException.Code.FAILED_PRECONDITION -> AiFailure.DISABLED
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> AiFailure.TIMEOUT
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.INTERNAL -> AiFailure.UNAVAILABLE
            else -> AiFailure.UNKNOWN
        }
    }

    // ---- response mapping (defensive: the wire format is untyped JSON) -------------------

    private fun Map<*, *>.toTaskDraft(): TaskDraft {
        val title = (this["title"] as? String).orEmpty()
        return TaskDraft(
            title = title,
            description = (this["description"] as? String).orEmpty(),
            categoryId = (this["categoryId"] as? String)?.takeIf { it.isNotBlank() },
            priority = (this["priority"] as? String)?.let { raw ->
                Priority.values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            },
            dueAt = (this["dueAt"] as? Number)?.toLong(),
            reminderAt = (this["reminderAt"] as? Number)?.toLong(),
            subTasks = stringList("subTasks"),
            confidence = ((this["confidence"] as? Number)?.toFloat() ?: 0.5f).coerceIn(0f, 1f),
            source = when ((this["source"] as? String)?.uppercase()) {
                "CACHE" -> DraftSource.CACHE
                "LARGE_MODEL" -> DraftSource.LARGE_MODEL
                "RULES" -> DraftSource.RULES
                else -> DraftSource.SMALL_MODEL
            },
            explanations = stringList("explanations")
        )
    }

    private fun Map<*, *>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() } ?: emptyList()
}
