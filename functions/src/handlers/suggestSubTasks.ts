import { subTasksCacheKey } from "../cache/cacheKey";
import type { ResponseCache } from "../cache/responseCache";
import { CACHE_TTL, INPUT_LIMITS, MODEL_CALL } from "../config";
import { dayKeyFor, isValidTimeZone, type RateLimiter } from "../gateway/rateLimiter";
import { optionalText, screenInput } from "../guardrails/input";
import { validateSubTasks } from "../guardrails/output";
import { hashUid, utcDayKey, type AiRequestEvent, type Metrics, type Outcome } from "../observability/metrics";
import { buildSubTasksPrompt } from "../prompts/subTasks";
import { extractJson, type ModelGateway } from "../providers/modelGateway";
import { chooseTier } from "../router/intentRouter";
import { GatewayError, type SuggestSubTasksResponse, type UserCtx } from "../types";

export interface SubTasksDeps {
  limiter: RateLimiter;
  cache: ResponseCache;
  gateway: ModelGateway;
  metrics: Metrics;
  now?: () => number;
}

/**
 * Sub-task breakdown. Same pipeline as parse, but the cache is **global**: "Plan Mom's
 * birthday" produces the same good answer for everyone, which is where a semantic cache
 * pays for itself first.
 */
export async function handleSuggestSubTasks(data: unknown, user: UserCtx, deps: SubTasksDeps): Promise<SuggestSubTasksResponse> {
  const now = deps.now ?? Date.now;
  const startedAt = now();
  const obj = typeof data === "object" && data !== null ? (data as Record<string, unknown>) : {};
  const timeZone = isValidTimeZone(obj.timeZone) ? obj.timeZone : "UTC";
  const dayKey = dayKeyFor(startedAt, timeZone);

  const event: AiRequestEvent = {
    event: "ai_request",
    fn: "aiSuggestSubTasks",
    uidHash: hashUid(user.uid),
    plan: user.plan,
    intent: "BREAK_DOWN_TASK",
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

  const screened = screenInput(obj.title, INPUT_LIMITS.parseMaxChars);
  if (!screened.ok) {
    await finish("guardrail_blocked", screened.reason);
    throw new GatewayError("invalid-argument", screened.reason ?? "invalid title");
  }
  const title = screened.text;
  const description = optionalText(obj.description, INPUT_LIMITS.subTasksMaxChars);
  event.inputChars = title.length + description.length;
  event.guardrailFlags = screened.flags;

  const decision = await deps.limiter.check(user.uid, user.plan, dayKey);
  if (!decision.allowed) {
    const reason = decision.reason ?? "RATE_LIMITED";
    await finish(reason === "BUDGET_EXCEEDED" ? "budget_exceeded" : "rate_limited");
    throw new GatewayError("resource-exhausted", "AI limit reached", { reason, retryAfterSec: decision.retryAfterSec ?? null });
  }

  const route = chooseTier({ intent: "BREAK_DOWN_TASK", text: `${title} ${description}`, plan: user.plan, flags: screened.flags });
  if (route.tier === "RULES") {
    event.tier = "RULES";
    await finish("rules_only", route.reason);
    return { subTasks: [], source: "RULES" };
  }
  event.tier = route.tier;

  const key = subTasksCacheKey(title, description);
  const cached = await deps.cache.get<string[]>(key);
  if (cached) {
    event.cacheHit = true;
    event.tier = "CACHE";
    await finish("cache_hit");
    return { subTasks: cached, source: "CACHE" };
  }

  const prompt = buildSubTasksPrompt(title, description);
  let content: string;
  try {
    const result = await deps.gateway.complete("SMALL_MODEL", {
      system: prompt.system,
      user: prompt.user,
      maxTokens: MODEL_CALL.subTasksMaxTokens,
      temperature: 0.2,
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

  let raw: unknown;
  try {
    raw = extractJson(content);
  } catch {
    await finish("invalid_output", "non-JSON model output");
    throw new GatewayError("unavailable", "AI returned an unreadable answer");
  }
  const subTasks = validateSubTasks((raw as { subTasks?: unknown })?.subTasks);

  await deps.cache.set(key, subTasks, CACHE_TTL.subTasksMs, { kind: "subtasks", scope: "global", model: event.model });
  await finish("ok");
  return { subTasks, source: "SMALL_MODEL" };
}
