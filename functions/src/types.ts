/**
 * Shared types for the Daymark AI gateway.
 * Pure – no Firebase imports – so every module that uses them is unit-testable.
 */

export type Plan = "free" | "pro";

export type Intent = "CREATE_TASK" | "BREAK_DOWN_TASK" | "PLAN_DAY" | "UNSUPPORTED";

/** Rung of the "minimum sufficient intelligence" ladder chosen for a request. */
export type Tier = "RULES" | "SMALL_MODEL" | "LARGE_MODEL";

export type DraftSource = "RULES" | "CACHE" | "SMALL_MODEL" | "LARGE_MODEL";

/** Must stay in sync with the Android `Priority` enum and the Firestore rule. */
export type Priority = "LOW" | "MEDIUM" | "HIGH" | "URGENT";
export const PRIORITIES: readonly Priority[] = ["LOW", "MEDIUM", "HIGH", "URGENT"];

export interface CategoryRef {
  id: string;
  name: string;
}

export interface ParseTaskRequest {
  text: string;
  timeZone: string;
  nowMillis: number;
  categories: CategoryRef[];
}

/** Wire format returned to the app – mirrors `domain/model/ai/TaskDraft.kt`. */
export interface TaskDraft {
  title: string;
  description: string;
  categoryId: string | null;
  priority: Priority | null;
  dueAt: number | null;
  reminderAt: number | null;
  subTasks: string[];
  confidence: number;
  source: DraftSource;
  explanations: string[];
}

export interface SuggestSubTasksRequest {
  title: string;
  description: string;
}

export interface SuggestSubTasksResponse {
  subTasks: string[];
  source: DraftSource;
}

export interface UserCtx {
  uid: string;
  plan: Plan;
}

export type GatewayErrorCode =
  | "invalid-argument"
  | "resource-exhausted"
  | "unavailable"
  | "unauthenticated"
  | "failed-precondition"
  | "internal";

/**
 * Transport-agnostic error. `index.ts` maps it to `HttpsError`; the Android client maps
 * the code (+ `details.reason`) to `AiFailure`.
 */
export class GatewayError extends Error {
  constructor(
    public readonly code: GatewayErrorCode,
    message: string,
    public readonly details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = "GatewayError";
  }
}
