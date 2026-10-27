import type { CacheEntry, CacheStore, CacheWriteMeta } from "../cache/responseCache";
import type { UsageRecord, UsageStore } from "../gateway/rateLimiter";
import type { AiRequestEvent, MetricsSink } from "../observability/metrics";
import type { FetchLike } from "../providers/modelGateway";

/** In-memory ports so the whole pipeline is testable without emulators. */

export class InMemoryUsageStore implements UsageStore {
  records = new Map<string, UsageRecord>();
  tokenCommits: { uid: string; dayKey: string; tokens: number }[] = [];

  async update<T>(uid: string, fn: (current: UsageRecord | null) => { next: UsageRecord; result: T }): Promise<T> {
    const { next, result } = fn(this.records.get(uid) ?? null);
    this.records.set(uid, next);
    return result;
  }

  async addTokens(uid: string, dayKey: string, tokens: number): Promise<void> {
    this.tokenCommits.push({ uid, dayKey, tokens });
    const rec = this.records.get(uid);
    if (rec) rec.dayTokens += tokens;
  }
}

export class InMemoryCacheStore implements CacheStore {
  entries = new Map<string, { entry: CacheEntry<unknown>; meta: CacheWriteMeta }>();
  async get<T>(key: string): Promise<CacheEntry<T> | null> {
    const hit = this.entries.get(key);
    return hit ? (hit.entry as CacheEntry<T>) : null;
  }
  async set<T>(key: string, entry: CacheEntry<T>, meta: CacheWriteMeta): Promise<void> {
    this.entries.set(key, { entry, meta });
  }
}

export class InMemoryMetricsSink implements MetricsSink {
  events: AiRequestEvent[] = [];
  aggregates: { dayKey: string; event: AiRequestEvent }[] = [];
  log(event: AiRequestEvent): void {
    this.events.push(event);
  }
  async aggregate(dayKey: string, event: AiRequestEvent): Promise<void> {
    this.aggregates.push({ dayKey, event });
  }
}

export interface FakeResponseSpec {
  status: number;
  /** Body JSON; defaults to a chat-completions envelope around `content`. */
  content?: string;
  body?: unknown;
  usage?: { prompt_tokens: number; completion_tokens: number };
  /** Simulate a hang → the gateway's AbortController should time it out. */
  hang?: boolean;
  /** Throw a network error instead of responding. */
  networkError?: boolean;
}

/** A fetch that answers from a queue, recording every call. */
export function fakeFetch(queue: FakeResponseSpec[]): FetchLike & { calls: { url: string; body: Record<string, unknown>; authorization: string }[] } {
  const calls: { url: string; body: Record<string, unknown>; authorization: string }[] = [];
  const fn = (async (url: string, init: RequestInit): Promise<Response> => {
    const headers = (init.headers ?? {}) as Record<string, string>;
    calls.push({ url, body: JSON.parse(String(init.body)), authorization: headers.authorization });
    const spec = queue.shift();
    if (!spec) throw new Error("fakeFetch: no response queued");
    if (spec.networkError) throw new TypeError("fetch failed");
    if (spec.hang) {
      return new Promise<Response>((_, reject) => {
        init.signal?.addEventListener("abort", () => {
          const err = new Error("aborted");
          err.name = "AbortError";
          reject(err);
        });
      });
    }
    const body =
      spec.body ??
      ({
        choices: [{ message: { content: spec.content ?? "" } }],
        usage: spec.usage ?? { prompt_tokens: 100, completion_tokens: 20 },
      } as unknown);
    return new Response(JSON.stringify(body), { status: spec.status, headers: { "content-type": "application/json" } });
  }) as FetchLike & { calls: typeof calls };
  fn.calls = calls;
  return fn;
}
