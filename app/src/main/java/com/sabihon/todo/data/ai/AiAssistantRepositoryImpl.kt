package com.sabihon.todo.data.ai

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.di.IoDispatcher
import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.DraftSource
import com.sabihon.todo.domain.model.ai.TaskDraft
import com.sabihon.todo.domain.repository.AiAssistantRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-device **router** of the AI ladder (see docs/ai/02-daymark-ai-system-design.md §5.6).
 *
 * Order of operations for [parseTask]:
 *  1. Tier 0 – [RuleBasedTaskParser] always runs (0 ms, offline, free).
 *  2. If Tier 0 is confident, the input is tiny, or flags say "local only" → return it.
 *  3. Local LRU cache (keyed by text + zone + local date, because "tomorrow" moves).
 *  4. Circuit breaker – skip the network during an outage.
 *  5. Remote gateway with a hard timeout; result is sanitised (client-side output guardrail)
 *     and merged with Tier 0 (explicit rule matches win, the model fills the gaps).
 *  6. Any failure → the Tier-0 draft flagged [TaskDraft.isDegraded] with a friendly note.
 *
 * The method therefore never fails for non-blank input – the UI always has something to show.
 */
@Singleton
class AiAssistantRepositoryImpl @Inject constructor(
    private val ruleParser: RuleBasedTaskParser,
    private val gateway: AiGateway,
    private val flags: AiFeatureFlags,
    private val breaker: CircuitBreaker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AiAssistantRepository {

    private val cache = LruCache<String, TaskDraft>(AiConfig.CACHE_SIZE)

    override suspend fun parseTask(text: String, context: AiContext): Result<TaskDraft> = withContext(ioDispatcher) {
        val input = text.trim()
        if (input.isEmpty()) {
            return@withContext Result.Error(IllegalArgumentException("Nothing to parse"))
        }

        // 1. Tier 0 ------------------------------------------------------------------
        val local = ruleParser.parse(input, context)

        // 2. Is Tier 0 sufficient? --------------------------------------------------
        val wordCount = input.split(Regex("\\s+")).count { it.isNotBlank() }
        val localOnly = !flags.aiEnabled || !flags.remoteTierEnabled
        if (localOnly || local.confidence >= AiConfig.CONFIDENT_THRESHOLD || wordCount <= AiConfig.MAX_WORDS_FOR_LOCAL_ONLY) {
            return@withContext Result.Success(local)
        }

        // 3. Cache -------------------------------------------------------------------
        val key = cacheKey(input, context)
        cache.get(key)?.let { hit ->
            return@withContext Result.Success(hit.copy(source = DraftSource.CACHE))
        }

        // 4. Circuit breaker ----------------------------------------------------------
        if (breaker.isOpen()) {
            return@withContext Result.Success(degrade(local, "Smart fill is resting – used quick parse"))
        }

        // 5. Remote tier --------------------------------------------------------------
        try {
            val remote = withTimeout(AiConfig.REMOTE_TIMEOUT_MS) { gateway.parseTask(input, context) }
            breaker.recordSuccess()
            val merged = merge(local = local, remote = sanitize(remote, context))
            cache.put(key, merged)
            Result.Success(merged)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                breaker.recordFailure()
                Result.Success(degrade(local, "AI took too long – used quick parse"))
            } else {
                throw e
            }
        } catch (e: AiGatewayException) {
            when (e.failure) {
                AiFailure.RATE_LIMITED -> Result.Success(degrade(local, "You're going fast – AI will be back in a minute"))
                AiFailure.BUDGET_EXCEEDED -> Result.Success(degrade(local, "Daily AI limit reached – used quick parse"))
                AiFailure.INVALID_INPUT -> Result.Success(degrade(local, "AI couldn't read that – used quick parse"))
                AiFailure.DISABLED -> Result.Success(degrade(local, "AI is turned off right now"))
                AiFailure.UNAUTHENTICATED -> Result.Success(degrade(local, "Sign in again to use AI"))
                else -> {
                    breaker.recordFailure()
                    Result.Success(degrade(local, "AI is unavailable – used quick parse"))
                }
            }
        } catch (e: Exception) {
            breaker.recordFailure()
            Result.Success(degrade(local, "AI is unavailable – used quick parse"))
        }
    }

    override suspend fun suggestSubTasks(title: String, description: String): Result<List<String>> = withContext(ioDispatcher) {
        if (title.isBlank()) return@withContext Result.Success(emptyList())
        if (!flags.aiEnabled || !flags.remoteTierEnabled || breaker.isOpen()) {
            return@withContext Result.Success(emptyList())
        }
        try {
            val items = withTimeout(AiConfig.REMOTE_TIMEOUT_MS) { gateway.suggestSubTasks(title.trim(), description.trim()) }
            breaker.recordSuccess()
            Result.Success(items.map { it.trim().take(MAX_SUBTASK_CHARS) }.filter { it.isNotEmpty() }.distinct().take(MAX_SUBTASKS))
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                breaker.recordFailure()
                Result.Error(e, "AI took too long")
            } else {
                throw e
            }
        } catch (e: AiGatewayException) {
            if (e.failure == AiFailure.UNAVAILABLE || e.failure == AiFailure.UNKNOWN) breaker.recordFailure()
            Result.Error(e, e.message)
        } catch (e: Exception) {
            breaker.recordFailure()
            Result.Error(e, e.message)
        }
    }

    // ---------------------------------------------------------------------------------

    private fun degrade(local: TaskDraft, note: String): TaskDraft =
        local.copy(isDegraded = true, explanations = local.explanations + note)

    /**
     * Client-side output guardrail. The server validates too, but the device must never
     * trust the wire: clamp lengths, drop unknown categories, reject absurd dates.
     */
    internal fun sanitize(remote: TaskDraft, context: AiContext): TaskDraft {
        val knownIds = context.categories.map { it.id }.toSet()
        val minDate = context.nowMillis - ONE_YEAR_MS
        val maxDate = context.nowMillis + 2 * ONE_YEAR_MS
        val dueAt = remote.dueAt?.takeIf { it in minDate..maxDate }
        val reminderAt = remote.reminderAt?.takeIf { it in minDate..maxDate && (dueAt == null || it <= dueAt) }
        return remote.copy(
            title = remote.title.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE_CHARS),
            description = remote.description.trim().take(MAX_DESCRIPTION_CHARS),
            categoryId = remote.categoryId?.takeIf { it in knownIds },
            dueAt = dueAt,
            reminderAt = reminderAt,
            subTasks = remote.subTasks.map { it.trim().take(MAX_SUBTASK_CHARS) }.filter { it.isNotEmpty() }.distinct().take(MAX_SUBTASKS),
            confidence = remote.confidence.coerceIn(0f, 1f)
        )
    }

    /** Deterministic beats probabilistic on explicit fields; the model fills the gaps. */
    internal fun merge(local: TaskDraft, remote: TaskDraft): TaskDraft {
        val title = remote.title.ifBlank { local.title }
        val dueAt = local.dueAt ?: remote.dueAt
        val explanations = LinkedHashSet<String>()
        explanations.addAll(local.explanations)
        // Keep the model's notes only for fields the rules did not already explain.
        remote.explanations.forEach { note ->
            val prefix = note.substringBefore(':', missingDelimiterValue = note).trim().lowercase(Locale.ROOT)
            val alreadyExplained = local.explanations.any {
                it.substringBefore(':', missingDelimiterValue = it).trim().lowercase(Locale.ROOT) == prefix
            }
            if (!alreadyExplained) explanations.add(note)
        }
        return TaskDraft(
            title = title,
            description = remote.description.ifBlank { local.description },
            categoryId = local.categoryId ?: remote.categoryId,
            priority = local.priority ?: remote.priority,
            dueAt = dueAt,
            reminderAt = remote.reminderAt?.takeIf { dueAt == null || it <= dueAt },
            subTasks = remote.subTasks.ifEmpty { local.subTasks },
            confidence = maxOf(local.confidence, remote.confidence),
            source = remote.source,
            isDegraded = false,
            explanations = explanations.toList()
        )
    }

    private fun cacheKey(input: String, context: AiContext): String {
        val zone = try { ZoneId.of(context.zoneId) } catch (e: Exception) { ZoneId.systemDefault() }
        val localDate = Instant.ofEpochMilli(context.nowMillis).atZone(zone).toLocalDate()
        val normalized = input.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}#]+"), " ").trim()
        val categoriesHash = context.categories.joinToString("|") { it.id }.hashCode()
        return "$normalized|${context.zoneId}|$localDate|$categoriesHash"
    }

    /** Tiny access-ordered LRU; pure JVM so the repository is unit-testable. */
    private class LruCache<K, V>(private val maxSize: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
        }
        @Synchronized fun get(key: K): V? = map[key]
        @Synchronized fun put(key: K, value: V) { map[key] = value }
    }

    private companion object {
        const val MAX_TITLE_CHARS = 120
        const val MAX_DESCRIPTION_CHARS = 1_000
        const val MAX_SUBTASK_CHARS = 80
        const val MAX_SUBTASKS = 8
        const val ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000
    }
}
