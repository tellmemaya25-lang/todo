package com.sabihon.todo.data.ai

/**
 * Minimal circuit breaker for the remote AI tier.
 *
 * After [failureThreshold] consecutive failures the circuit *opens* for [openForMillis];
 * while open, callers skip the network entirely and use Tier 0. The first call after
 * the window closes is allowed through (half-open); a success resets the counter.
 *
 * This is the on-device half of the video's "fallback provider" idea: during a
 * provider or function outage the app stays instant and stops burning requests
 * against a failing backend.
 */
class CircuitBreaker(
    private val failureThreshold: Int = AiConfig.BREAKER_FAILURE_THRESHOLD,
    private val openForMillis: Long = AiConfig.BREAKER_OPEN_MS,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var consecutiveFailures = 0
    private var openedAt: Long? = null

    /** True while the circuit is open and the window has not elapsed. */
    fun isOpen(): Boolean = synchronized(lock) {
        val opened = openedAt ?: return false
        if (nowProvider() - opened >= openForMillis) {
            // Half-open: let one request probe the backend.
            openedAt = null
            consecutiveFailures = failureThreshold - 1
            false
        } else {
            true
        }
    }

    fun recordSuccess() = synchronized(lock) {
        consecutiveFailures = 0
        openedAt = null
    }

    fun recordFailure() = synchronized(lock) {
        consecutiveFailures += 1
        if (consecutiveFailures >= failureThreshold) {
            openedAt = nowProvider()
        }
    }
}
