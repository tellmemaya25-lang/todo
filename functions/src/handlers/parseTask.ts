import { parseCacheKey } from "../cache/cacheKey";
import type { ResponseCache } from "../cache/responseCache";
import { CACHE_TTL, INPUT_LIMITS, MODEL_CALL } from "../config";
import { dayKeyFor, isValidTimeZone, localDateFor, type RateLimiter } from "../gateway/rateLimiter";
import { screenInput } from "../guardrails/input";
import { validateTaskDraft } from "../guardrails/output";
import { hashUid, utcDayKey, type AiRequestEvent, type Metrics, type Outcome } from "../observability/metrics";
import { buildParseTaskPrompt } from "../prompts/parseTask";
import { extractJson, type ModelGateway } from "../providers/modelGateway";
import { chooseTier, classifyIntent } from "../router/intentRouter";
import { GatewayError, type CategoryRef, type ParseTaskRequest, type TaskDraft, type UserCtx } from "../types";

/**
 * The gateway pipeline for Smart Quick-Add, in the order the video builds it:
 *
 *   auth (done by caller) → input guardrails → rate limit / budget → router →
 *   cache → model gateway (primary → fallback) → output guardrails → cache.put +
 *   usage.commit + metrics
 *
 * Every dependency is injected, so the whole pipeline runs in unit tests with fakes.
 */
export interface ParseDeps {
  limiter: RateLimiter;
  cache: ResponseCache;
  gateway: ModelGateway;
  metrics: Metrics;
  now?: () => number;
}

export async function handleParseTask(data: unknown, user: UserCtx, deps: ParseDeps): Promise<TaskDraft> {
  const now = deps.now ?? Date.now;
  const startedAt = now();
  const req = normalizeRequest(data, startedAt);
  const dayKey = dayKeyFor(req.nowMillis, req.timeZone);

  const event: AiRequestEvent = {
    event: "ai_request",
    fn: "aiParseTask",
    uidHash: hashUid(user.uid),
    plan: user.plan,
    intent: "CREATE_TASK",
    tier: "NONE",
    cacheHit: false,
    fallbackUsed: false,
    guardrailFlags: [],
    inputChars: 0,
    latencyMs: 0,
    tokensIn: 0,
    tokensOut: 0,
    costMicros: 0,
    outcome: "ok",
  };

  const finish = async (outcome: Outcome, errorMessage?: string): Promise<void> => {
    event.outcome = outcome;
    event.latencyMs = now() - startedAt;
    if (errorMessage) event.errorMessage = errorMessage.slice(0, 200);
    await deps.metrics.record(utcDayKey(startedAt), event);
  };

  // 1. Input guardrails ---------------------------------------------------------------
  const screened = screenInput(req.text, INPUT_LIMITS.parseMaxChars);
  if (!screened.ok) {
    await finish("guardrail_blocked", screened.reason);
    throw new GatewayError("invalid-argument", screened.reason ?? "invalid text");
  }
  const text = screened.text;
  event.inputChars = text.length;
  event.guardrailFlags = screened.flags;

  // 2. Rate limit & budget ------------------------------------------------------------
  const decision = await deps.limiter.check(user.uid, user.plan, dayKey);
  if (!decision.allowed) {
    const reason = decision.reason ?? "RATE_LIMITED";
    await finish(reason === "BUDGET_EXCEEDED" ? "budget_exceeded" : "rate_limited");
    throw new GatewayError(
      "resource-exhausted",
      reason === "BUDGET_EXCEEDED" ? "Daily AI allowance used up" : "Too many requests – slow down",
      { reason, retryAfterSec: decision.retryAfterSec ?? null },
    );
  }

  // 3. Router -------------------------------------------------------------------------
  event.intent = classifyIntent(text) === "UNSUPPORTED" ? "UNSUPPORTED" : "CREATE_TASK";
  const route = chooseTier({ intent: "CREATE_TASK", text, plan: user.plan, flags: screened.flags });
  if (route.tier === "RULES") {
    // The device already has Tier 0; tell it to use that rather than echoing the text back.
    event.tier = "RULES";
    await finish("rules_only", route.reason);
    throw new GatewayError("invalid-argument", "Handled on device", { reason: "RULES_ONLY" });
  }
  event.tier = route.tier;

  // 4. Cache --------------------------------------------------------------------------
  const localDate = localDateFor(req.nowMillis, req.timeZone);
  const key = parseCacheKey(text, req.timeZone, localDate, req.categories.map((c) => c.id));
  const cached = await deps.cache.get<TaskDraft>(key);
  if (cached) {
    event.cacheHit = true;
    event.tier = "CACHE";
    await finish("cache_hit");
    return { ...cached, source: "CACHE" };
  }

  // 5. Model gateway ------------------------------------------------------------------
  const prompt = buildParseTaskPrompt({
    text,
    timeZone: req.timeZone,
    nowIso: isoInZone(req.nowMillis, req.timeZone),
    weekday: weekdayInZone(req.nowMillis, req.timeZone),
    categories: req.categories,
  });

  let content: string;
  try {
    const result = await deps.gateway.complete(route.tier, {
      system: prompt.system,
      user: prompt.user,
      maxTokens: MODEL_CALL.parseMaxTokens,
      temperature: 0,
    });
    content = result.content;
    event.provider = result.provider;
    event.model = result.model;
    event.fallbackUsed = result.fallbackUsed;
    event.tokensIn = result.tokensIn;
    event.tokensOut = result.tokensOut;
    event.costMicros = result.costMicros;
    event.modelLatencyMs = result.latencyMs;
    await deps.limiter.commitTokens(user.uid, dayKey, result.tokensIn + result.tokensOut);
  } catch (err) {
    await finish("provider_error", err instanceof Error ? err.message : String(err));
    throw new GatewayError("unavailable", "AI is temporarily unavailable");
  }

  // 6. Output guardrails --------------------------------------------------------------
  let raw: unknown;
  try {
    raw = extractJson(content);
  } catch {
    await finish("invalid_output", "non-JSON model output");
    throw new GatewayError("unavailable", "AI returned an unreadable answer");
  }
  const draft = validateTaskDraft(raw, {
    nowMillis: req.nowMillis,
    allowedCategoryIds: new Set(req.categories.map((c) => c.id)),
    source: route.tier,
    fallbackTitle: text,
  });

  // 7. Remember + account -------------------------------------------------------------
  await deps.cache.set(key, draft, CACHE_TTL.parseMs, { kind: "parse", scope: user.uid, model: event.model });
  await finish("ok");
  return draft;
}

// -----------------------------------------------------------------------------------------

/** Shape-check the callable payload; tolerate a sloppy client, never trust it. */
export function normalizeRequest(data: unknown, serverNow: number): ParseTaskRequest {
  const obj = typeof data === "object" && data !== null ? (data as Record<string, unknown>) : {};

  const timeZone = isValidTimeZone(obj.timeZone) ? obj.timeZone : "UTC";

  let nowMillis = typeof obj.nowMillis === "number" && Number.isFinite(obj.nowMillis) ? obj.nowMillis : serverNow;
  if (Math.abs(nowMillis - serverNow) > INPUT_LIMITS.clockSkewToleranceMs) nowMillis = serverNow;

  const categories: CategoryRef[] = [];
  if (Array.isArray(obj.categories)) {
    for (const item of obj.categories.slice(0, INPUT_LIMITS.maxCategories)) {
      if (typeof item !== "object" || item === null) continue;
      const c = item as Record<string, unknown>;
      if (typeof c.id !== "string" || typeof c.name !== "string") continue;
      const id = c.id.trim().slice(0, INPUT_LIMITS.categoryIdMaxChars);
      const name = c.name.replace(/\s+/g, " ").trim().slice(0, INPUT_LIMITS.categoryNameMaxChars);
      if (id && name) categories.push({ id, name });
    }
  }

  return { text: typeof obj.text === "string" ? obj.text : "", timeZone, nowMillis, categories };
}

export function isoInZone(millis: number, timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZoneName: "longOffset",
  }).formatToParts(new Date(millis));
  const get = (type: string): string => parts.find((p) => p.type === type)?.value ?? "";
  const rawOffset = get("timeZoneName"); // "GMT+08:00" or "GMT"
  const offset = rawOffset === "GMT" ? "+00:00" : rawOffset.replace("GMT", "");
  return `${get("year")}-${get("month")}-${get("day")}T${get("hour")}:${get("minute")}:${get("second")}${offset}`;
}

export function weekdayInZone(millis: number, timeZone: string): string {
  return new Intl.DateTimeFormat("en-US", { timeZone, weekday: "long" }).format(new Date(millis));
}
