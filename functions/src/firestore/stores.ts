import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import type { CacheEntry, CacheStore, CacheWriteMeta } from "../cache/responseCache";
import type { UsageRecord, UsageStore } from "../gateway/rateLimiter";
import { aggregateIncrements, type AiRequestEvent, type MetricsSink } from "../observability/metrics";

/**
 * Firestore implementations of the gateway's storage ports.
 *
 * Collections (all top-level, so the app's `users/{uid}/**` rule never exposes them;
 * `firestore.rules` denies client access explicitly; the Admin SDK bypasses rules):
 *
 *   ai_usage/{uid}          rate-limit windows + daily token budget
 *   ai_cache/{key}          response cache, `expiresAt` drives a Firestore TTL policy
 *   ai_metrics/{yyyy-mm-dd} daily counters & latency buckets
 *   ai_user_state/{uid}     tasksVersion (bumped by the CDC trigger)
 */
export const COLLECTIONS = {
  usage: "ai_usage",
  cache: "ai_cache",
  metrics: "ai_metrics",
  userState: "ai_user_state",
} as const;

export class FirestoreUsageStore implements UsageStore {
  constructor(private readonly db: Firestore) {}

  async update<T>(uid: string, fn: (current: UsageRecord | null) => { next: UsageRecord; result: T }): Promise<T> {
    const ref = this.db.collection(COLLECTIONS.usage).doc(uid);
    return this.db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const current = snap.exists ? (snap.data() as UsageRecord) : null;
      const { next, result } = fn(current);
      tx.set(ref, { ...next, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      return result;
    });
  }

  async addTokens(uid: string, dayKey: string, tokens: number): Promise<void> {
    const ref = this.db.collection(COLLECTIONS.usage).doc(uid);
    // If the day rolled over between check() and here, the next check() resets the counter anyway.
    await ref.set({ dayKey, dayTokens: FieldValue.increment(tokens) }, { merge: true });
  }
}

export class FirestoreCacheStore implements CacheStore {
  constructor(private readonly db: Firestore) {}

  async get<T>(key: string): Promise<CacheEntry<T> | null> {
    const snap = await this.db.collection(COLLECTIONS.cache).doc(key).get();
    if (!snap.exists) return null;
    const data = snap.data() as { value?: string; expiresAt?: Timestamp };
    if (!data.value || !data.expiresAt) return null;
    return { value: JSON.parse(data.value) as T, expiresAt: data.expiresAt.toMillis() };
  }

  async set<T>(key: string, entry: CacheEntry<T>, meta: CacheWriteMeta): Promise<void> {
    await this.db
      .collection(COLLECTIONS.cache)
      .doc(key)
      .set({
        kind: meta.kind,
        scope: meta.scope,
        model: meta.model ?? null,
        // Stored as a JSON string: opaque to queries, cheap to write, no field-count limits.
        value: JSON.stringify(entry.value),
        createdAt: FieldValue.serverTimestamp(),
        // Enable a TTL policy on this field (see docs) and Firestore deletes expired docs for free.
        expiresAt: Timestamp.fromMillis(entry.expiresAt),
      });
  }
}

export class FirestoreMetricsSink implements MetricsSink {
  constructor(private readonly db: Firestore) {}

  log(event: AiRequestEvent): void {
    // Structured log → Cloud Logging → log-based metrics / alerts. No user text, ever.
    logger.info("ai_request", event);
  }

  async aggregate(dayKey: string, event: AiRequestEvent): Promise<void> {
    const increments = aggregateIncrements(event);
    const update: Record<string, unknown> = { updatedAt: FieldValue.serverTimestamp() };
    for (const [field, n] of Object.entries(increments)) update[field] = FieldValue.increment(n);
    await this.db.collection(COLLECTIONS.metrics).doc(dayKey).set(update, { merge: true });
  }
}

/** CDC target: anything cached from the user's task list keys on this version. */
export async function bumpTasksVersion(db: Firestore, uid: string): Promise<void> {
  await db
    .collection(COLLECTIONS.userState)
    .doc(uid)
    .set({ tasksVersion: FieldValue.increment(1), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
}
