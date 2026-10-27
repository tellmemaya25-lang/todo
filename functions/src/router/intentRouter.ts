import { LIMITS } from "../config";
import type { Intent, Plan, Tier } from "../types";

/**
 * Router / orchestrator – decides *which rung of the ladder* handles a request.
 * Deliberately rule-based (zero cost, explainable). Upgrade to a tiny classifier only
 * if the rules measurably misroute.
 */
export function classifyIntent(text: string): Intent {
  const t = text.trim();
  if (!t) return "UNSUPPORTED";
  if (/^(break\s*down|split|steps?\s+(for|to)|how\s+do\s+i)\b/i.test(t)) return "BREAK_DOWN_TASK";
  if (/\b(plan|organi[sz]e|schedule)\s+(my|the|today'?s)\s+(day|morning|afternoon|evening)\b/i.test(t)) return "PLAN_DAY";
  if (/\bwhat\s+should\s+i\s+(do|focus\s+on)\s+(today|now|first)\b/i.test(t)) return "PLAN_DAY";
  return "CREATE_TASK";
}

export interface RouteInput {
  intent: Intent;
  text: string;
  plan: Plan;
  /** From input guardrails. */
  flags: string[];
  /** For PLAN_DAY – how big is the problem. */
  openTaskCount?: number;
}

export interface RouteDecision {
  tier: Tier;
  reason: string;
}

export function chooseTier(input: RouteInput): RouteDecision {
  const limits = LIMITS[input.plan] ?? LIMITS.free;

  if (input.flags.includes("INJECTION_PATTERN")) {
    return { tier: "RULES", reason: "injection pattern – keep away from the model" };
  }
  if (input.flags.includes("MOSTLY_SYMBOLS")) {
    return { tier: "RULES", reason: "input is mostly symbols" };
  }

  switch (input.intent) {
    case "PLAN_DAY": {
      const complex = (input.openTaskCount ?? 0) > 15 || /\b(before|after|between|except|unless|around)\b/i.test(input.text);
      if (complex && limits.largeModelAllowed) return { tier: "LARGE_MODEL", reason: "complex day plan" };
      return { tier: "SMALL_MODEL", reason: "simple day plan" };
    }
    case "CREATE_TASK": {
      // Long free-form notes with several asks benefit from a stronger model; short ones don't.
      const multiAsk = input.text.length > 300 && /\b(and|then|also)\b/i.test(input.text);
      if (multiAsk && limits.largeModelAllowed) return { tier: "LARGE_MODEL", reason: "long multi-ask note" };
      return { tier: "SMALL_MODEL", reason: "standard quick-add" };
    }
    case "BREAK_DOWN_TASK":
      return { tier: "SMALL_MODEL", reason: "breakdown" };
    case "UNSUPPORTED":
    default:
      return { tier: "RULES", reason: "unsupported intent" };
  }
}
