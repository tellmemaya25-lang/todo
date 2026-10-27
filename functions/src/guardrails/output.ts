import { OUTPUT_LIMITS } from "../config";
import { PRIORITIES, type DraftSource, type Priority, type TaskDraft } from "../types";

/**
 * Output guardrails: the model's JSON is untrusted. Everything is coerced into the exact
 * contract the app (and the Firestore rules) expect. Unknown keys are dropped; impossible
 * values become `null` rather than errors – a partially useful draft beats a failure.
 */
export interface OutputContext {
  nowMillis: number;
  allowedCategoryIds: Set<string>;
  source: DraftSource;
  /** Used when the model returns an empty/unsafe title. */
  fallbackTitle: string;
}

export function validateTaskDraft(raw: unknown, ctx: OutputContext): TaskDraft {
  const obj = isRecord(raw) ? raw : {};

  const title = cleanLine(obj.title, OUTPUT_LIMITS.titleMaxChars) || cleanLine(ctx.fallbackTitle, OUTPUT_LIMITS.titleMaxChars);
  const description = cleanText(obj.description, OUTPUT_LIMITS.descriptionMaxChars);

  const categoryId = typeof obj.categoryId === "string" && ctx.allowedCategoryIds.has(obj.categoryId) ? obj.categoryId : null;

  const dueAt = toMillisInWindow(obj.dueAt, ctx.nowMillis);
  let reminderAt = toMillisInWindow(obj.reminderAt, ctx.nowMillis);
  if (reminderAt !== null && dueAt !== null && reminderAt > dueAt) reminderAt = null;

  return {
    title,
    description,
    categoryId,
    priority: toPriority(obj.priority),
    dueAt,
    reminderAt,
    subTasks: validateSubTasks(obj.subTasks),
    confidence: clamp01(typeof obj.confidence === "number" ? obj.confidence : 0.6),
    source: ctx.source,
    explanations: toStringList(obj.explanations, OUTPUT_LIMITS.maxExplanations, OUTPUT_LIMITS.explanationMaxChars),
  };
}

export function validateSubTasks(raw: unknown): string[] {
  return toStringList(raw, OUTPUT_LIMITS.maxSubTasks, OUTPUT_LIMITS.subTaskMaxChars);
}

// ---------------------------------------------------------------------------------------

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/** Single line, no markdown/URLs, bounded. */
function cleanLine(v: unknown, max: number): string {
  if (typeof v !== "string") return "";
  return v
    .replace(/https?:\/\/\S+/gi, "")
    .replace(/[`*_#>|]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, max);
}

function cleanText(v: unknown, max: number): string {
  if (typeof v !== "string") return "";
  return v.replace(/https?:\/\/\S+/gi, "").replace(/[ \t]+/g, " ").trim().slice(0, max);
}

function toPriority(v: unknown): Priority | null {
  if (typeof v !== "string") return null;
  const upper = v.trim().toUpperCase();
  return (PRIORITIES as readonly string[]).includes(upper) ? (upper as Priority) : null;
}

/** Accepts epoch millis or ISO-8601 (with offset); rejects anything outside the sanity window. */
function toMillisInWindow(v: unknown, nowMillis: number): number | null {
  let millis: number | null = null;
  if (typeof v === "number" && Number.isFinite(v)) {
    millis = v > 1e11 ? v : v * 1000; // tolerate epoch seconds
  } else if (typeof v === "string" && v.trim()) {
    const parsed = Date.parse(v.trim());
    millis = Number.isNaN(parsed) ? null : parsed;
  }
  if (millis === null) return null;
  const min = nowMillis - OUTPUT_LIMITS.pastWindowMs;
  const max = nowMillis + OUTPUT_LIMITS.futureWindowMs;
  return millis >= min && millis <= max ? Math.round(millis) : null;
}

function toStringList(v: unknown, maxItems: number, maxChars: number): string[] {
  if (!Array.isArray(v)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of v) {
    const s = cleanLine(item, maxChars);
    if (!s) continue;
    const key = s.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(s);
    if (out.length >= maxItems) break;
  }
  return out;
}

function clamp01(n: number): number {
  if (!Number.isFinite(n)) return 0;
  return Math.min(1, Math.max(0, n));
}
