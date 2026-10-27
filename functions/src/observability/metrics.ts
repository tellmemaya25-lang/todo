import { createHash } from "node:crypto";
import type { Intent } from "../types";

/**
 * Observability: one structured event per request, plus a cheap daily aggregate.
 *
 * Privacy rule: **never** log the user's text. Only hashes, lengths, flags and numbers.
 *
 *  - `log()` → Cloud Logging (jsonPayload.event = "ai_request") → build log-based metrics
 *    and alert policies on latencyMs (P95 > 3 s), costMicros (daily budget), outcome.
 *  - `aggregate()` → one Firestore doc per day with counters and latency buckets, so an
 *    approximate P95 and today's spend can be read from a single document.
 */
export type Outcome =
  | "ok"
  | "cache_hit"
  | "rules_only"
  | "rate_limited"
  | "budget_exceeded"
  | "guardrail_blocked"
  | "provider_error"
  | "invalid_output"
  | "error";

export interface AiRequestEvent {
  event: "ai_request";
  fn: string;
  uidHash: string;
  plan: string;
  intent: Intent;
  tier: "RULES" | "SMALL_MODEL" | "LARGE_MODEL" | "CACHE" | "NONE";
  provider?: string;
  model?: string;
  cacheHit: boolean;
  fallbackUsed: boolean;
  guardrailFlags: string[];
  inputChars: number;
  latencyMs: number;
  modelLatencyMs?: number;
  tokensIn: number;
  tokensOut: number;
  costMicros: number;
  outcome: Outcome;
  errorMessage?: string;
}

export interface MetricsSink {
  log(event: AiRequestEvent): void;
  aggregate(dayKey: string, event: AiRequestEvent): Promise<void>;
}

export type LatencyBucket = "lat_lt_500" | "lat_lt_1000" | "lat_lt_3000" | "lat_ge_3000";

export function latencyBucket(ms: number): LatencyBucket {
  if (ms < 500) return "lat_lt_500";
  if (ms < 1000) return "lat_lt_1000";
  if (ms < 3000) return "lat_lt_3000";
  return "lat_ge_3000";
}

/** Stable pseudonym for a user – enough to count distinct users, useless for identification. */
export function hashUid(uid: string): string {
  return createHash("sha256").update(uid).digest("hex").slice(0, 12);
}

/** Daily counters derived from one event; the sink applies them as atomic increments. */
export function aggregateIncrements(event: AiRequestEvent): Record<string, number> {
  const inc: Record<string, number> = {
    requests: 1,
    tokensIn: event.tokensIn,
    tokensOut: event.tokensOut,
    costMicros: event.costMicros,
    latencySumMs: event.latencyMs,
    [latencyBucket(event.latencyMs)]: 1,
    [`outcome_${event.outcome}`]: 1,
    [`tier_${event.tier}`]: 1,
  };
  if (event.cacheHit) inc.cacheHits = 1;
  if (event.fallbackUsed) inc.fallbacks = 1;
  if (event.outcome === "provider_error" || event.outcome === "error" || event.outcome === "invalid_output") inc.errors = 1;
  if (event.model) inc[`model_${event.model.replace(/[^\w.-]/g, "_")}`] = 1;
  return inc;
}

export class Metrics {
  constructor(private readonly sink: MetricsSink) {}

  /** Never throws, never blocks the response for long. */
  async record(dayKey: string, event: AiRequestEvent): Promise<void> {
    try {
      this.sink.log(event);
    } catch {
      /* logging must not fail requests */
    }
    try {
      await this.sink.aggregate(dayKey, event);
    } catch {
      /* best effort */
    }
  }
}

/** UTC day key for metrics (ops-facing; users' local days are used only for limits). */
export function utcDayKey(nowMillis: number): string {
  return new Date(nowMillis).toISOString().slice(0, 10);
}
