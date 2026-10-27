package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.TaskDraft

/**
 * Static configuration for the AI feature. Values that may need to change without a
 * release (kill switch, remote tier) live in [AiFeatureFlags] instead.
 */
object AiConfig {
    /** Cloud Functions region – keep it next to Firestore and close to users (PH → Singapore). */
    const val REGION = "asia-southeast1"
    const val FN_PARSE_TASK = "aiParseTask"
    const val FN_SUGGEST_SUBTASKS = "aiSuggestSubTasks"

    /** Tier-0 confidence at or above which the remote tier is skipped. */
    const val CONFIDENT_THRESHOLD = 0.75f
    /** Inputs this short never benefit from a model. */
    const val MAX_WORDS_FOR_LOCAL_ONLY = 3
    const val REMOTE_TIMEOUT_MS = 8_000L
    const val CACHE_SIZE = 64

    const val BREAKER_FAILURE_THRESHOLD = 3
    const val BREAKER_OPEN_MS = 2 * 60_000L
}

/**
 * Runtime switches. The default implementation is "all on"; wire it to Firebase
 * Remote Config in Phase 2 so the feature can be disabled without shipping an APK.
 */
interface AiFeatureFlags {
    /** Master kill switch – when false, Smart fill is rules-only and the UI may hide the action. */
    val aiEnabled: Boolean
    /** When false, never call the network; Tier 0 only (e.g. during a provider incident). */
    val remoteTierEnabled: Boolean

    object Default : AiFeatureFlags {
        override val aiEnabled: Boolean = true
        override val remoteTierEnabled: Boolean = true
    }
}

/** Why a remote call failed – mapped from transport errors so upper layers never see them. */
enum class AiFailure {
    RATE_LIMITED,
    BUDGET_EXCEEDED,
    UNAUTHENTICATED,
    INVALID_INPUT,
    DISABLED,
    UNAVAILABLE,
    TIMEOUT,
    UNKNOWN
}

class AiGatewayException(
    val failure: AiFailure,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: failure.name, cause)

/**
 * Transport to the AI gateway (Cloud Functions). Implementations throw
 * [AiGatewayException] for every failure so the repository can degrade gracefully.
 */
interface AiGateway {
    suspend fun parseTask(text: String, context: AiContext): TaskDraft
    suspend fun suggestSubTasks(title: String, description: String): List<String>
}
