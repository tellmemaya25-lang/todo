import assert from "node:assert/strict";
import { test } from "node:test";
import { chooseTier, classifyIntent } from "./intentRouter";

test("classifies intents with rules", () => {
  assert.equal(classifyIntent("Call mom tomorrow"), "CREATE_TASK");
  assert.equal(classifyIntent("break down: move apartment"), "BREAK_DOWN_TASK");
  assert.equal(classifyIntent("plan my day"), "PLAN_DAY");
  assert.equal(classifyIntent("what should I focus on today"), "PLAN_DAY");
  assert.equal(classifyIntent(""), "UNSUPPORTED");
});

test("injection or symbol flags keep the request away from the model", () => {
  assert.equal(chooseTier({ intent: "CREATE_TASK", text: "x", plan: "pro", flags: ["INJECTION_PATTERN"] }).tier, "RULES");
  assert.equal(chooseTier({ intent: "CREATE_TASK", text: "x", plan: "pro", flags: ["MOSTLY_SYMBOLS"] }).tier, "RULES");
});

test("minimum sufficient intelligence: small by default, large only when complex and allowed", () => {
  const long = "a".repeat(301) + " and then also";
  assert.equal(chooseTier({ intent: "CREATE_TASK", text: "Call mom", plan: "pro", flags: [] }).tier, "SMALL_MODEL");
  assert.equal(chooseTier({ intent: "CREATE_TASK", text: long, plan: "free", flags: [] }).tier, "SMALL_MODEL");
  assert.equal(chooseTier({ intent: "CREATE_TASK", text: long, plan: "pro", flags: [] }).tier, "LARGE_MODEL");
  assert.equal(chooseTier({ intent: "PLAN_DAY", text: "plan my day", plan: "pro", flags: [], openTaskCount: 5 }).tier, "SMALL_MODEL");
  assert.equal(chooseTier({ intent: "PLAN_DAY", text: "plan my day", plan: "pro", flags: [], openTaskCount: 30 }).tier, "LARGE_MODEL");
  assert.equal(chooseTier({ intent: "PLAN_DAY", text: "plan my day before 3pm", plan: "free", flags: [] }).tier, "SMALL_MODEL");
  assert.equal(chooseTier({ intent: "UNSUPPORTED", text: "", plan: "pro", flags: [] }).tier, "RULES");
});
