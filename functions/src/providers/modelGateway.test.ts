import assert from "node:assert/strict";
import { test } from "node:test";
import type { ProviderConfig } from "../config";
import { fakeFetch } from "../testing/fakes";
import { costMicros, extractJson, ModelGateway, ProviderError } from "./modelGateway";

const primary: ProviderConfig = { name: "primary", baseUrl: "https://p.example/v1/", apiKey: "pk", model: "small-1", jsonMode: true, inputPricePerMTokUsd: 0.1, outputPricePerMTokUsd: 0.4 };
const fallback: ProviderConfig = { name: "fallback", baseUrl: "https://f.example/v1", apiKey: "fk", model: "small-2", jsonMode: false, inputPricePerMTokUsd: 0.15, outputPricePerMTokUsd: 0.6 };
const large: ProviderConfig = { ...primary, name: "primary-large", model: "large-1", inputPricePerMTokUsd: 0.3, outputPricePerMTokUsd: 2.5 };
const noSleep = async (): Promise<void> => {};
const req = { system: "sys", user: "{}", maxTokens: 100 };

test("happy path: primary answers, request is OpenAI-compatible, cost is accounted", async () => {
  const f = fakeFetch([{ status: 200, content: '{"ok":true}', usage: { prompt_tokens: 1000, completion_tokens: 500 } }]);
  const gw = new ModelGateway({ small: [primary, fallback], large: [large] }, { fetchImpl: f, sleep: noSleep });
  const r = await gw.complete("SMALL_MODEL", req);
  assert.equal(r.content, '{"ok":true}');
  assert.equal(r.provider, "primary");
  assert.equal(r.fallbackUsed, false);
  assert.equal(r.attempts, 1);
  assert.equal(r.costMicros, Math.round(1000 * 0.1 + 500 * 0.4)); // 300 micro-dollars
  assert.equal(f.calls[0].url, "https://p.example/v1/chat/completions");
  assert.equal(f.calls[0].authorization, "Bearer pk");
  assert.equal(f.calls[0].body.model, "small-1");
  assert.deepEqual(f.calls[0].body.response_format, { type: "json_object" });
  assert.equal(f.calls[0].body.temperature, 0);
});

test("429 is retried once, then the fallback provider is used", async () => {
  const f = fakeFetch([{ status: 429 }, { status: 429 }, { status: 200, content: "{}" }]);
  const gw = new ModelGateway({ small: [primary, fallback], large: [] }, { fetchImpl: f, sleep: noSleep });
  const r = await gw.complete("SMALL_MODEL", req);
  assert.equal(r.provider, "fallback");
  assert.equal(r.fallbackUsed, true);
  assert.equal(r.attempts, 3);
  assert.equal("response_format" in f.calls[2].body, false); // fallback has jsonMode=false
});

test("non-retryable 4xx skips straight to the next provider", async () => {
  const f = fakeFetch([{ status: 400 }, { status: 200, content: "{}" }]);
  const gw = new ModelGateway({ small: [primary, fallback], large: [] }, { fetchImpl: f, sleep: noSleep });
  const r = await gw.complete("SMALL_MODEL", req);
  assert.equal(r.provider, "fallback");
  assert.equal(r.attempts, 2);
});

test("timeouts and network errors are retryable; everything failing raises ProviderError", async () => {
  const f = fakeFetch([{ status: 200, hang: true }, { status: 200, networkError: true }, { status: 503 }, { status: 500 }]);
  const gw = new ModelGateway({ small: [primary, fallback], large: [] }, { fetchImpl: f, sleep: noSleep, timeoutMs: 20 });
  await assert.rejects(gw.complete("SMALL_MODEL", req), (e: unknown) => e instanceof ProviderError && /all providers failed/.test(e.message));
  assert.equal(f.calls.length, 4);
});

test("LARGE tier falls back to the small chain before failing", async () => {
  const f = fakeFetch([{ status: 500 }, { status: 500 }, { status: 200, content: "{}" }]);
  const gw = new ModelGateway({ small: [primary], large: [large] }, { fetchImpl: f, sleep: noSleep });
  const r = await gw.complete("LARGE_MODEL", req);
  assert.equal(r.model, "small-1");
  assert.equal(r.fallbackUsed, true);
});

test("empty content counts as a failure", async () => {
  const f = fakeFetch([{ status: 200, content: "" }, { status: 200, content: "" }]);
  const gw = new ModelGateway({ small: [primary], large: [] }, { fetchImpl: f, sleep: noSleep });
  await assert.rejects(gw.complete("SMALL_MODEL", req));
});

test("extractJson tolerates fences and prose but rejects garbage", () => {
  assert.deepEqual(extractJson('```json\n{"a":1}\n```'), { a: 1 });
  assert.deepEqual(extractJson('Sure! Here you go: {"a":[1,2]} hope it helps'), { a: [1, 2] });
  assert.deepEqual(extractJson(' {"a":1} '), { a: 1 });
  assert.throws(() => extractJson("no json here"), SyntaxError);
});

test("costMicros", () => {
  assert.equal(costMicros(primary, 1_000_000, 0), 100_000); // $0.10
  assert.equal(costMicros(large, 0, 1_000_000), 2_500_000); // $2.50
});
