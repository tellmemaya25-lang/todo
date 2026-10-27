/**
 * Input guardrails.
 *
 * Philosophy for a to-do app: the user's text is *data*, never instructions. We therefore
 * do not try to "detect bad intent" and refuse (a title like "ignore the previous invoice"
 * is legitimate). We:
 *   1. reject what can never be valid (non-string, empty, too long),
 *   2. strip control characters,
 *   3. *flag* suspicious patterns so the router can keep them away from the model.
 */
export interface InputScreen {
  ok: boolean;
  reason?: string;
  text: string;
  flags: string[];
}

const INJECTION_PATTERNS: RegExp[] = [
  /ignore\s+(all\s+)?(the\s+)?(previous|prior|above|earlier)\s+(instructions?|prompts?|rules?)/i,
  /disregard\s+(the\s+)?(previous|prior|above|system|your)/i,
  /system\s+prompt/i,
  /you\s+are\s+now\s+/i,
  /\bact\s+as\s+(a|an|the)\b/i,
  /<\/?\s*(system|assistant|user|tool)\s*>/i,
  /\[\s*(system|inst)\s*\]/i,
  /```/,
  /\bjailbreak\b/i,
  /reveal\s+(your|the)\s+(prompt|instructions|rules)/i,
  /\bdeveloper\s+mode\b/i,
];

// Control characters except tab/newline/carriage return.
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

export function screenInput(raw: unknown, maxChars: number): InputScreen {
  if (typeof raw !== "string") {
    return { ok: false, reason: "text must be a string", text: "", flags: [] };
  }
  const text = raw.replace(CONTROL_CHARS, "").replace(/\s+/g, " ").trim();
  if (text.length === 0) {
    return { ok: false, reason: "text is empty", text, flags: [] };
  }
  if (text.length > maxChars) {
    return { ok: false, reason: `text exceeds ${maxChars} characters`, text, flags: [] };
  }

  const flags: string[] = [];
  if (INJECTION_PATTERNS.some((p) => p.test(text))) flags.push("INJECTION_PATTERN");
  if (/https?:\/\//i.test(text)) flags.push("URL");
  const symbolCount = text.replace(/[\p{L}\p{N}\s]/gu, "").length;
  if (symbolCount > text.length * 0.5) flags.push("MOSTLY_SYMBOLS");

  return { ok: true, text, flags };
}

/** Validate an optional free-text field with a cap; returns "" when absent. */
export function optionalText(raw: unknown, maxChars: number): string {
  if (typeof raw !== "string") return "";
  return raw.replace(CONTROL_CHARS, "").replace(/\s+/g, " ").trim().slice(0, maxChars);
}
