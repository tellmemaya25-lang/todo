import assert from "node:assert/strict";
import { test } from "node:test";
import { aggregateIncrements, hashUid, latencyBucket, Metrics, utcDayKey, type AiRequestEvent } from "./metrics";
import { InMemoryMetricsSink } from "../testing/fakes";

const base: AiRequestEvent = {
  event: "ai_request", fn: "aiParseTask", uidHash: hashUid("u"), plan: "free", intent: "CREATE_TASK", tier: "SMALL_MODEL",
  provider: "primary", model: "gemini-2.5-flash-lite", cacheHit: false, fallbackUsed: true, guardrailFlags: [], inputChars: 20,
  latencyMs: 1200, tokensIn: 300, tokensOut: 50, costMicros: 50, outcome: "ok",
};

test("latency buckets approximate a P95 from one document", () => {
  assert.equal(latencyBucket(100), "lat_lt_500");
  assert.equal(latencyBucket(999), "lat_lt_1000");
  assert.equal(latencyBucket(2999), "lat_lt_3000");
  assert.equal(latencyBucket(3000), "lat_ge_3000");
});

test("aggregate increments count what the dashboard needs", () => {
  const inc = aggregateIncrements(base);
  assert.equal(inc.requests, 1);
  assert.equal(inc.tokensIn, 300);
  assert.equal(inc.costMicros, 50);
  assert.equal(inc.lat_lt_3000, 1);
  assert.equal(inc.fallbacks, 1);
  assert.equal(inc.outcome_ok, 1);
  assert.equal(inc.tier_SMALL_MODEL, 1);
  assert.equal(inc["model_gemini-2.5-flash-lite"], 1);
  assert.equal("errors" in inc, false);
  assert.equal(aggregateIncrements({ ...base, outcome: "provider_error" }).errors, 1);
});

test("hashUid is stable, short and not reversible by inspection; metrics never throw", async () => {
  assert.equal(hashUid("abc"), hashUid("abc"));
  assert.equal(hashUid("abc").length, 12);
  assert.notEqual(hashUid("abc"), "abc");
  const sink = new InMemoryMetricsSink();
  sink.aggregate = async () => { throw new Error("firestore down"); };
  await new Metrics(sink).record("2026-10-27", base); // must not throw
  assert.equal(sink.events.length, 1);
  assert.equal(utcDayKey(Date.UTC(2026, 9, 27, 23, 59)), "2026-10-27");
});
