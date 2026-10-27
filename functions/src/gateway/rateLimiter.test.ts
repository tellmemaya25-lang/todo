import assert from "node:assert/strict";
import { test } from "node:test";
import { InMemoryUsageStore } from "../testing/fakes";
import { dayKeyFor, isValidTimeZone, RateLimiter } from "./rateLimiter";

const limits = {
  free: { perMinute: 3, perDay: 5, tokensPerDay: 1000, largeModelAllowed: false },
  pro: { perMinute: 10, perDay: 100, tokensPerDay: 100000, largeModelAllowed: true },
};

test("per-minute window blocks the 4th request and resets after 60s", async () => {
  let now = 1_000_000;
  const limiter = new RateLimiter(new InMemoryUsageStore(), limits, () => now);
  for (let i = 0; i < 3; i++) assert.equal((await limiter.check("u1", "free", "2026-10-27")).allowed, true);
  const blocked = await limiter.check("u1", "free", "2026-10-27");
  assert.equal(blocked.allowed, false);
  assert.equal(blocked.reason, "RATE_LIMITED");
  assert.ok((blocked.retryAfterSec ?? 0) >= 1 && (blocked.retryAfterSec ?? 0) <= 60);

  now += 60_001;
  assert.equal((await limiter.check("u1", "free", "2026-10-27")).allowed, true);
});

test("daily request budget is enforced and resets with the day key", async () => {
  let now = 0;
  const limiter = new RateLimiter(new InMemoryUsageStore(), limits, () => now);
  let allowed = 0;
  for (let i = 0; i < 8; i++) {
    now += 61_000; // never hit the minute limit
    const d = await limiter.check("u1", "free", "2026-10-27");
    if (d.allowed) allowed++;
    else assert.equal(d.reason, "BUDGET_EXCEEDED");
  }
  assert.equal(allowed, 5);
  now += 61_000;
  assert.equal((await limiter.check("u1", "free", "2026-10-28")).allowed, true);
});

test("token budget blocks once committed tokens exceed the plan", async () => {
  const store = new InMemoryUsageStore();
  const limiter = new RateLimiter(store, limits, () => 5_000);
  assert.equal((await limiter.check("u1", "free", "d")).allowed, true);
  await limiter.commitTokens("u1", "d", 1200);
  const d = await limiter.check("u1", "free", "d");
  assert.equal(d.allowed, false);
  assert.equal(d.reason, "BUDGET_EXCEEDED");
  // A pro user with the same usage is fine.
  assert.equal((await limiter.check("u2", "pro", "d")).allowed, true);
});

test("users are isolated", async () => {
  const limiter = new RateLimiter(new InMemoryUsageStore(), limits, () => 0);
  for (let i = 0; i < 3; i++) await limiter.check("a", "free", "d");
  assert.equal((await limiter.check("a", "free", "d")).allowed, false);
  assert.equal((await limiter.check("b", "free", "d")).allowed, true);
});

test("day key follows the user's zone", () => {
  const t = Date.UTC(2026, 9, 27, 17, 30); // 17:30Z = 01:30 next day in Manila
  assert.equal(dayKeyFor(t, "UTC"), "2026-10-27");
  assert.equal(dayKeyFor(t, "Asia/Manila"), "2026-10-28");
  assert.equal(dayKeyFor(t, "Not/AZone"), "2026-10-27");
  assert.equal(isValidTimeZone("Asia/Manila"), true);
  assert.equal(isValidTimeZone("Mars/Olympus"), false);
  assert.equal(isValidTimeZone(42), false);
});
