import assert from "node:assert/strict";
import { test } from "node:test";
import { validateTaskDraft } from "./output";

const now = Date.UTC(2026, 9, 27, 2, 0, 0); // 2026-10-27T02:00Z = 10:00 Manila
const ctx = { nowMillis: now, allowedCategoryIds: new Set(["c1", "c2"]), source: "SMALL_MODEL" as const, fallbackTitle: "raw text" };

test("coerces a well-formed answer and accepts ISO dates with offsets", () => {
  const d = validateTaskDraft(
    {
      title: "Call the dentist",
      categoryId: "c1",
      priority: "urgent",
      dueAt: "2026-10-28T17:00:00+08:00",
      reminderAt: "2026-10-28T16:00:00+08:00",
      subTasks: ["Find number", "Call"],
      confidence: 0.9,
      explanations: ["Due: Tomorrow 5:00 PM"],
      extraKey: "dropped",
    },
    ctx,
  );
  assert.equal(d.title, "Call the dentist");
  assert.equal(d.categoryId, "c1");
  assert.equal(d.priority, "URGENT");
  assert.equal(d.dueAt, Date.parse("2026-10-28T17:00:00+08:00"));
  assert.equal(d.reminderAt, Date.parse("2026-10-28T16:00:00+08:00"));
  assert.deepEqual(d.subTasks, ["Find number", "Call"]);
  assert.equal(d.confidence, 0.9);
  assert.equal(d.source, "SMALL_MODEL");
  assert.equal("extraKey" in d, false);
});

test("rejects unknown category, bad priority, absurd dates and reminder after due", () => {
  const d = validateTaskDraft(
    { title: "x", categoryId: "nope", priority: "CRITICAL", dueAt: "2031-01-01T00:00:00Z", reminderAt: now + 1000 },
    ctx,
  );
  assert.equal(d.categoryId, null);
  assert.equal(d.priority, null);
  assert.equal(d.dueAt, null);
  assert.equal(d.reminderAt, now + 1000); // valid on its own once dueAt is gone

  const r = validateTaskDraft({ title: "x", dueAt: now + 1000, reminderAt: now + 5000 }, ctx);
  assert.equal(r.reminderAt, null);
});

test("sanitises title (urls, markdown, length) and falls back to the input text", () => {
  const d = validateTaskDraft({ title: "**Buy** milk https://evil.example `now` " + "a".repeat(200) }, ctx);
  assert.ok(!d.title.includes("http"));
  assert.ok(!d.title.includes("*"));
  assert.ok(d.title.length <= 120);
  const empty = validateTaskDraft({ title: "   " }, ctx);
  assert.equal(empty.title, "raw text");
  const garbage = validateTaskDraft("not an object", ctx);
  assert.equal(garbage.title, "raw text");
});

test("bounds and de-duplicates sub-tasks and explanations", () => {
  const d = validateTaskDraft(
    { title: "t", subTasks: Array.from({ length: 20 }, (_, i) => `step ${i % 10}`), explanations: ["a", "a", "b", "c", "d", "e", "f"] },
    ctx,
  );
  assert.equal(d.subTasks.length, 8);
  assert.equal(new Set(d.subTasks).size, 8);
  assert.equal(d.explanations.length, 5);
});

test("tolerates epoch seconds and clamps confidence", () => {
  const seconds = Math.floor((now + 3600_000) / 1000);
  const d = validateTaskDraft({ title: "t", dueAt: seconds, confidence: 7 }, ctx);
  assert.equal(d.dueAt, seconds * 1000);
  assert.equal(d.confidence, 1);
});
