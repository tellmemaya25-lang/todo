import assert from "node:assert/strict";
import { test } from "node:test";
import { normalizeText, parseCacheKey, subTasksCacheKey } from "./cacheKey";

test("normalisation makes trivially different phrasings collide", () => {
  assert.equal(normalizeText("  Call MOM, tomorrow!!  "), "call mom tomorrow");
  assert.equal(normalizeText("Buy rice #grocery"), "buy rice #grocery");
});

test("parse key depends on text, zone, local date and categories – and never contains the text", () => {
  const a = parseCacheKey("Call mom tomorrow", "Asia/Manila", "2026-10-27", ["c1", "c2"]);
  assert.equal(a, parseCacheKey("call  mom tomorrow!", "Asia/Manila", "2026-10-27", ["c2", "c1"]));
  assert.notEqual(a, parseCacheKey("Call mom tomorrow", "Asia/Manila", "2026-10-28", ["c1", "c2"]));
  assert.notEqual(a, parseCacheKey("Call mom tomorrow", "UTC", "2026-10-27", ["c1", "c2"]));
  assert.notEqual(a, parseCacheKey("Call mom tomorrow", "Asia/Manila", "2026-10-27", ["c1"]));
  assert.ok(a.startsWith("parse:"));
  assert.ok(!a.includes("mom"));
});

test("sub-task key is global (no user/date) and ignores case/punctuation", () => {
  assert.equal(subTasksCacheKey("Plan Mom's birthday", ""), subTasksCacheKey("plan moms birthday!", ""));
  assert.notEqual(subTasksCacheKey("Plan Mom's birthday", ""), subTasksCacheKey("Plan Dad's birthday", ""));
});
