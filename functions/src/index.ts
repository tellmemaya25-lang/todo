/**
 * Daymark AI gateway – Firebase Cloud Functions (2nd gen) entry point.
 *
 * Everything interesting lives in the pure modules; this file only:
 *   - reads params/secrets,
 *   - enforces auth (and App Check once enrolled),
 *   - wires Firestore-backed stores into the handlers,
 *   - maps GatewayError → HttpsError,
 *   - registers the CDC trigger.
 *
 * Deploy:  firebase deploy --only functions
 * Secrets: firebase functions:secrets:set AI_PRIMARY_API_KEY  (and AI_FALLBACK_API_KEY – enter "none" to disable)
 */
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { defineBoolean, defineSecret, defineString } from "firebase-functions/params";
import { setGlobalOptions } from "firebase-functions/v2";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";

import { ResponseCache } from "./cache/responseCache";
import type { ModelRoutingConfig, ProviderConfig } from "./config";
import { bumpTasksVersion, FirestoreCacheStore, FirestoreMetricsSink, FirestoreUsageStore } from "./firestore/stores";
import { RateLimiter } from "./gateway/rateLimiter";
import { handleParseTask } from "./handlers/parseTask";
import { handleSuggestSubTasks } from "./handlers/suggestSubTasks";
import { Metrics } from "./observability/metrics";
import { ModelGateway } from "./providers/modelGateway";
import { GatewayError, type Plan, type UserCtx } from "./types";

initializeApp();

/** Singapore – closest region to the Philippines; keep Firestore in the same region. */
const REGION = "asia-southeast1";
setGlobalOptions({ region: REGION, maxInstances: 20 });

// ---- Params & secrets (set once per project; secrets live in Secret Manager) ------------
const AI_DISABLED = defineBoolean("AI_DISABLED", { default: false, description: "Kill switch for all AI endpoints" });

const AI_PRIMARY_API_KEY = defineSecret("AI_PRIMARY_API_KEY");
const AI_PRIMARY_BASE_URL = defineString("AI_PRIMARY_BASE_URL", {
  default: "https://generativelanguage.googleapis.com/v1beta/openai",
  description: "OpenAI-compatible base URL of the primary provider",
});
const AI_PRIMARY_MODEL = defineString("AI_PRIMARY_MODEL", { default: "gemini-2.5-flash-lite", description: "Small/cheap model (Tier 1)" });
const AI_PRIMARY_LARGE_MODEL = defineString("AI_PRIMARY_LARGE_MODEL", { default: "gemini-2.5-flash", description: "Capable model (Tier 2)" });
const AI_PRIMARY_PRICES = defineString("AI_PRIMARY_PRICES", { default: "0.10,0.40", description: "USD per 1M tokens: input,output (small model)" });
const AI_PRIMARY_LARGE_PRICES = defineString("AI_PRIMARY_LARGE_PRICES", { default: "0.30,2.50", description: "USD per 1M tokens: input,output (large model)" });

const AI_FALLBACK_API_KEY = defineSecret("AI_FALLBACK_API_KEY");
const AI_FALLBACK_BASE_URL = defineString("AI_FALLBACK_BASE_URL", { default: "https://api.openai.com/v1" });
const AI_FALLBACK_MODEL = defineString("AI_FALLBACK_MODEL", { default: "gpt-4o-mini" });
const AI_FALLBACK_PRICES = defineString("AI_FALLBACK_PRICES", { default: "0.15,0.60" });

const SECRETS = [AI_PRIMARY_API_KEY, AI_FALLBACK_API_KEY];

/** Review model names and prices at deploy time – they change faster than this code. */
function routingConfig(): ModelRoutingConfig {
  const primaryKey = AI_PRIMARY_API_KEY.value();
  const fallbackKey = AI_FALLBACK_API_KEY.value();
  const [pIn, pOut] = parsePrices(AI_PRIMARY_PRICES.value());
  const [lIn, lOut] = parsePrices(AI_PRIMARY_LARGE_PRICES.value());
  const [fIn, fOut] = parsePrices(AI_FALLBACK_PRICES.value());

  const primarySmall: ProviderConfig = {
    name: "primary",
    baseUrl: AI_PRIMARY_BASE_URL.value(),
    apiKey: primaryKey,
    model: AI_PRIMARY_MODEL.value(),
    jsonMode: true,
    inputPricePerMTokUsd: pIn,
    outputPricePerMTokUsd: pOut,
  };
  const primaryLarge: ProviderConfig = { ...primarySmall, name: "primary-large", model: AI_PRIMARY_LARGE_MODEL.value(), inputPricePerMTokUsd: lIn, outputPricePerMTokUsd: lOut };

  const small: ProviderConfig[] = [primarySmall];
  const large: ProviderConfig[] = [primaryLarge];
  if (fallbackKey && !/^(none|disabled|off)$/i.test(fallbackKey.trim())) {
    small.push({
      name: "fallback",
      baseUrl: AI_FALLBACK_BASE_URL.value(),
      apiKey: fallbackKey,
      model: AI_FALLBACK_MODEL.value(),
      jsonMode: true,
      inputPricePerMTokUsd: fIn,
      outputPricePerMTokUsd: fOut,
    });
  }
  return { small, large };
}

function parsePrices(raw: string): [number, number] {
  const [a, b] = raw.split(",").map((s) => Number(s.trim()));
  return [Number.isFinite(a) ? a : 0, Number.isFinite(b) ? b : 0];
}

// ---- Wiring --------------------------------------------------------------------------------
interface Deps {
  limiter: RateLimiter;
  cache: ResponseCache;
  gateway: ModelGateway;
  metrics: Metrics;
}

let depsSingleton: Deps | null = null;
function deps(): Deps {
  if (depsSingleton) return depsSingleton;
  const db = getFirestore();
  depsSingleton = {
    limiter: new RateLimiter(new FirestoreUsageStore(db)),
    cache: new ResponseCache(new FirestoreCacheStore(db)),
    gateway: new ModelGateway(routingConfig()),
    metrics: new Metrics(new FirestoreMetricsSink(db)),
  };
  return depsSingleton;
}

/** Auth: Firebase ID token is verified by the callable SDK; we only read the result. */
function requireUser(request: CallableRequest<unknown>): UserCtx {
  if (AI_DISABLED.value()) throw new HttpsError("failed-precondition", "AI features are turned off", { reason: "AI_DISABLED" });
  const auth = request.auth;
  if (!auth?.uid) throw new HttpsError("unauthenticated", "Sign in to use AI features");
  const plan: Plan = auth.token?.plan === "pro" ? "pro" : "free"; // custom claim; defaults to free
  return { uid: auth.uid, plan };
}

function toHttpsError(err: unknown): HttpsError {
  if (err instanceof HttpsError) return err;
  if (err instanceof GatewayError) return new HttpsError(err.code, err.message, err.details);
  logger.error("ai_gateway_unexpected_error", { message: err instanceof Error ? err.message : String(err) });
  return new HttpsError("internal", "Something went wrong");
}

const callableOptions = {
  secrets: SECRETS,
  timeoutSeconds: 30,
  memory: "256MiB" as const,
  // Flip to true once the app is registered with App Check (Play Integrity) – blocks non-app callers.
  enforceAppCheck: false,
  // minInstances: 1, // uncomment if cold starts hurt morning P95
};

/** Smart Quick-Add: free text → TaskDraft. */
export const aiParseTask = onCall(callableOptions, async (request) => {
  const user = requireUser(request);
  try {
    return await handleParseTask(request.data, user, deps());
  } catch (err) {
    throw toHttpsError(err);
  }
});

/** Sub-task suggestions for a task title. */
export const aiSuggestSubTasks = onCall(callableOptions, async (request) => {
  const user = requireUser(request);
  try {
    return await handleSuggestSubTasks(request.data, user, deps());
  } catch (err) {
    throw toHttpsError(err);
  }
});

/**
 * CDC: the user's task list changed → bump `tasksVersion`. Any cached answer derived from
 * the task list (daily plan, NL-search embeddings in later phases) includes that version in
 * its key, so invalidation is exact and free – nothing is flushed, nothing goes stale.
 */
export const onTaskWritten = onDocumentWritten({ document: "users/{uid}/tasks/{taskId}", region: REGION }, async (event) => {
  const uid = event.params.uid;
  try {
    await bumpTasksVersion(getFirestore(), uid);
  } catch (err) {
    logger.warn("cdc_bump_failed", { uidHash: uid.slice(0, 4) + "…", message: err instanceof Error ? err.message : String(err) });
  }
});
