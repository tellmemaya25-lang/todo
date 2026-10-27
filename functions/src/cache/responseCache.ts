/**
 * Response cache – the "semantic cache" rung, implemented in two layers:
 *
 *  1. **Exact-normalized** (this file, on by default): lower-cased, punctuation-stripped text →
 *     hashed key → Firestore doc with a TTL. Zero extra infrastructure, zero false positives.
 *  2. **Semantic** (optional, see [SemanticIndex]): embed the text and look for a stored
 *     neighbour above a cosine threshold. Firestore now supports this natively
 *     (`FieldValue.vector` + `findNearest`), so it is still "no new infrastructure" – but it
 *     costs an embedding call per miss, so enable it only where reuse is high (sub-task
 *     breakdowns across users), and never for personal, date-relative or tool-backed answers.
 */
export interface CacheEntry<T> {
  value: T;
  expiresAt: number;
}

export interface CacheWriteMeta {
  /** e.g. "parse" | "subtasks" | "plan" – lets CDC invalidate by kind. */
  kind: string;
  /** uid for personal entries, "global" for shareable ones. */
  scope: string;
  model?: string;
}

export interface CacheStore {
  get<T>(key: string): Promise<CacheEntry<T> | null>;
  set<T>(key: string, entry: CacheEntry<T>, meta: CacheWriteMeta): Promise<void>;
}

export class ResponseCache {
  constructor(
    private readonly store: CacheStore,
    private readonly now: () => number = Date.now,
  ) {}

  async get<T>(key: string): Promise<T | null> {
    try {
      const entry = await this.store.get<T>(key);
      if (!entry) return null;
      if (entry.expiresAt <= this.now()) return null; // expired but TTL sweep not run yet
      return entry.value;
    } catch {
      return null; // cache problems must never fail a request
    }
  }

  async set<T>(key: string, value: T, ttlMs: number, meta: CacheWriteMeta): Promise<void> {
    try {
      await this.store.set<T>(key, { value, expiresAt: this.now() + ttlMs }, meta);
    } catch {
      /* best effort */
    }
  }
}

/**
 * Hook for the semantic layer (Phase 2). Implement with Firestore vector search:
 *
 *   await col.add({ embedding: FieldValue.vector(vec), kind, scope, value, expiresAt });
 *   const hit = await col.where("kind", "==", kind)
 *     .findNearest({ vectorField: "embedding", queryVector: vec, limit: 1,
 *                    distanceMeasure: "COSINE", distanceResultField: "distance" }).get();
 *   // accept when distance <= 0.08 (cosine distance; ≈ 0.92 similarity)
 */
export interface SemanticIndex {
  lookup<T>(kind: string, text: string): Promise<T | null>;
  insert<T>(kind: string, text: string, value: T, ttlMs: number, scope: string): Promise<void>;
}
