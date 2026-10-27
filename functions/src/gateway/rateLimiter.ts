import { LIMITS, type PlanLimits } from "../config";
import type { Plan } from "../types";

/**
 * Per-user rate limiting and token budgets – the API-gateway rung of the design.
 *
 * Two kinds of limit, exactly as the video prescribes:
 *   - request *rate* (fixed 60 s window) → "you're going too fast",
 *   - daily *budget* (requests and tokens) → "you've used today's AI allowance".
 *
 * Storage is behind [UsageStore] so the limiter runs against Firestore in production and
 * an in-memory map in tests. At Daymark's scale one Firestore transaction per remote call is
 * fine; swap the store for Memorystore/Redis if you ever exceed ~50 req/s.
 */
export interface UsageRecord {
  minuteWindowStart: number;
  minuteCount: number;
  dayKey: string;
  dayRequests: number;
  dayTokens: number;
}

export interface UsageStore {
  /** Read-modify-write under a transaction; `fn` must be pure. */
  update<T>(uid: string, fn: (current: UsageRecord | null) => { next: UsageRecord; result: T }): Promise<T>;
  /** Fire-and-forget token accounting after the model call. */
  addTokens(uid: string, dayKey: string, tokens: number): Promise<void>;
}

export type LimitReason = "RATE_LIMITED" | "BUDGET_EXCEEDED";

export interface LimitDecision {
  allowed: boolean;
  reason?: LimitReason;
  /** Seconds until the caller may retry (minute window) – informational. */
  retryAfterSec?: number;
  remainingToday?: number;
}

const WINDOW_MS = 60_000;

export class RateLimiter {
  constructor(
    private readonly store: UsageStore,
    private readonly limits: Record<Plan, PlanLimits> = LIMITS,
    private readonly now: () => number = Date.now,
  ) {}

  /** Reserve one request. Call before doing any expensive work. */
  async check(uid: string, plan: Plan, dayKey: string): Promise<LimitDecision> {
    const limit = this.limits[plan] ?? this.limits.free;
    const now = this.now();

    return this.store.update<LimitDecision>(uid, (current) => {
      const base: UsageRecord = current ?? {
        minuteWindowStart: now,
        minuteCount: 0,
        dayKey,
        dayRequests: 0,
        dayTokens: 0,
      };

      // Roll the windows forward.
      const minuteExpired = now - base.minuteWindowStart >= WINDOW_MS;
      const minuteWindowStart = minuteExpired ? now : base.minuteWindowStart;
      const minuteCount = minuteExpired ? 0 : base.minuteCount;
      const sameDay = base.dayKey === dayKey;
      const dayRequests = sameDay ? base.dayRequests : 0;
      const dayTokens = sameDay ? base.dayTokens : 0;

      const rolled: UsageRecord = { minuteWindowStart, minuteCount, dayKey, dayRequests, dayTokens };

      if (dayTokens >= limit.tokensPerDay || dayRequests >= limit.perDay) {
        return { next: rolled, result: { allowed: false, reason: "BUDGET_EXCEEDED", remainingToday: 0 } };
      }
      if (minuteCount >= limit.perMinute) {
        const retryAfterSec = Math.max(1, Math.ceil((WINDOW_MS - (now - minuteWindowStart)) / 1000));
        return { next: rolled, result: { allowed: false, reason: "RATE_LIMITED", retryAfterSec } };
      }

      const next: UsageRecord = { ...rolled, minuteCount: minuteCount + 1, dayRequests: dayRequests + 1 };
      return { next, result: { allowed: true, remainingToday: limit.perDay - next.dayRequests } };
    });
  }

  async commitTokens(uid: string, dayKey: string, tokens: number): Promise<void> {
    if (tokens <= 0) return;
    await this.store.addTokens(uid, dayKey, tokens);
  }
}

/**
 * `yyyy-mm-dd` in the user's zone, so "daily" resets at *their* midnight.
 * Falls back to UTC for unknown zones.
 */
export function dayKeyFor(nowMillis: number, timeZone: string): string {
  return localDateFor(nowMillis, timeZone);
}

export function localDateFor(nowMillis: number, timeZone: string): string {
  const fmt = (() => {
    try {
      return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" });
    } catch {
      return new Intl.DateTimeFormat("en-CA", { timeZone: "UTC", year: "numeric", month: "2-digit", day: "2-digit" });
    }
  })();
  // en-CA formats as YYYY-MM-DD.
  return fmt.format(new Date(nowMillis));
}

export function isValidTimeZone(tz: unknown): tz is string {
  if (typeof tz !== "string" || !tz) return false;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}
