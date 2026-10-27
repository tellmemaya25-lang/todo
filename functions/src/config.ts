import type { Plan } from "./types";

/** "Who gets what access, at what rate, with what daily limit?" – answered per plan. */
export interface PlanLimits {
  perMinute: number;
  perDay: number;
  tokensPerDay: number;
  largeModelAllowed: boolean;
}

export const LIMITS: Record<Plan, PlanLimits> = {
  free: { perMinute: 10, perDay: 100, tokensPerDay: 100_000, largeModelAllowed: false },
  pro: { perMinute: 30, perDay: 1_000, tokensPerDay: 1_000_000, largeModelAllowed: true },
};

export const INPUT_LIMITS = {
  parseMaxChars: 500,
  subTasksMaxChars: 2_000,
  maxCategories: 50,
  categoryIdMaxChars: 64,
  categoryNameMaxChars: 40,
  /** Client clocks drift and can be forged; beyond this we use the server's clock. */
  clockSkewToleranceMs: 24 * 60 * 60 * 1000,
};

const DAY_MS = 24 * 60 * 60 * 1000;

export const OUTPUT_LIMITS = {
  titleMaxChars: 120,
  descriptionMaxChars: 1_000,
  subTaskMaxChars: 80,
  maxSubTasks: 8,
  maxExplanations: 5,
  explanationMaxChars: 60,
  pastWindowMs: 365 * DAY_MS,
  futureWindowMs: 2 * 365 * DAY_MS,
};

export const CACHE_TTL = {
  /** Keyed by local date, so a day is the natural upper bound. */
  parseMs: DAY_MS,
  /** Not personal → shared across users; safe to keep longer. */
  subTasksMs: 7 * DAY_MS,
};

export const MODEL_CALL = {
  timeoutMs: 6_000,
  maxAttemptsPerProvider: 2,
  parseMaxTokens: 300,
  subTasksMaxTokens: 250,
};

/** One OpenAI-compatible chat-completions endpoint. */
export interface ProviderConfig {
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  /** Send `response_format: {type: "json_object"}` – supported by OpenAI, Gemini (OpenAI endpoint), Groq, Mistral. */
  jsonMode: boolean;
  /** USD per 1M tokens – used for cost accounting / alerts only. Review at deploy time. */
  inputPricePerMTokUsd: number;
  outputPricePerMTokUsd: number;
}

/** Ordered chains: index 0 is the primary, the rest are fallbacks. */
export interface ModelRoutingConfig {
  small: ProviderConfig[];
  large: ProviderConfig[];
}
