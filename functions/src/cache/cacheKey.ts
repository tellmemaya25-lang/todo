import { createHash } from "node:crypto";

/**
 * Cache keys. Text is hashed, never stored in the key – the only copy of the user's words
 * server-side is the short-TTL cache *value*, scoped to that user.
 */
export function normalizeText(s: string): string {
  return s
    .normalize("NFKC")
    .toLowerCase()
    .replace(/['\u2019`]/g, "") // "mom's" → "moms", not "mom s"
    .replace(/[^\p{L}\p{N}#\s]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function sha256(s: string): string {
  return createHash("sha256").update(s, "utf8").digest("hex");
}

/**
 * Quick-add parse. "tomorrow" means something different tomorrow, so the user's local
 * date and zone are part of the key; so is the set of categories the result may reference.
 */
export function parseCacheKey(text: string, timeZone: string, localDate: string, categoryIds: string[]): string {
  const cats = [...categoryIds].sort().join(",");
  return "parse:" + sha256(`${normalizeText(text)}|${timeZone}|${localDate}|${cats}`);
}

/** Sub-task breakdowns are not personal → one global key per normalized title. */
export function subTasksCacheKey(title: string, description: string): string {
  return "subtasks:" + sha256(`${normalizeText(title)}|${normalizeText(description).slice(0, 200)}`);
}
