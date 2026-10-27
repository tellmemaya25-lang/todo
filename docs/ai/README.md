# Daymark AI — start here

| Doc | What it is |
|---|---|
| [01-video-breakdown.md](01-video-breakdown.md) | Segment‑by‑segment breakdown of codebasics' *AI System Design Tutorial* (gateway/auth, guardrails, semantic cache, CDC, HITL, model router, fallback provider, observability) + interview cheat‑sheet. |
| [02-daymark-ai-system-design.md](02-daymark-ai-system-design.md) | The design for Daymark: requirements, estimates, HLD/LLD, how each video component maps onto a Firebase‑only stack, roadmap, deploy steps, decision log. |

## Code map (Phase 1 — Smart Quick‑Add)

```
Android (app/src/main/java/com/sabihon/todo)
├── domain/model/ai/TaskDraft.kt                 TaskDraft, AiContext, DraftSource
├── domain/repository/AiAssistantRepository.kt   contract (no Firebase types)
├── domain/usecase/ParseTaskUseCase.kt           input guardrail + context → repository
├── data/ai/RuleBasedTaskParser.kt               Tier 0: offline NL → draft (dates, times, priority, category)
├── data/ai/AiAssistantRepositoryImpl.kt         on‑device router: rules → LRU cache → breaker → remote → degrade
├── data/ai/CallableAiGateway.kt                 Firebase Callable transport + error mapping
├── data/ai/CircuitBreaker.kt, AiGateway.kt      breaker, config, feature flags, failure enum
├── di/AiModule.kt                               Hilt wiring
└── ui/addedit/*                                 "Smart fill" action + explanation notes

Backend (functions/src) — Cloud Functions v2, asia-southeast1
├── index.ts                   aiParseTask / aiSuggestSubTasks callables, onTaskWritten (CDC)
├── handlers/                  pipeline orchestration per endpoint
├── guardrails/input.ts        length/control chars/injection flags (never logs text)
├── guardrails/output.ts       schema + enum + date‑range validation of model output
├── gateway/rateLimiter.ts     per‑user per‑minute / per‑day / token budgets (Firestore txn)
├── router/intentRouter.ts     intent + tier selection ("minimum sufficient intelligence")
├── cache/                     normalised cache keys, TTL cache, semantic‑cache hook
├── providers/modelGateway.ts  OpenAI‑compatible client, retries, timeout, provider fallback, cost
├── prompts/                   system prompts (user text is passed as JSON data, not instructions)
├── observability/metrics.ts   structured ai_request events + daily aggregates
├── firestore/stores.ts        Firestore adapters for the ports above
└── testing/fakes.ts + *.test.ts   in‑memory fakes; `npm test`
```

Run the backend tests with `cd functions && npm install && npm test`.
