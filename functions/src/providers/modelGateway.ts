import { MODEL_CALL, type ModelRoutingConfig, type ProviderConfig } from "../config";

/**
 * Model gateway – one place that talks to LLM providers.
 *
 *  - Provider-agnostic: every provider is an OpenAI-compatible chat-completions endpoint
 *    (OpenAI, Gemini's OpenAI endpoint, Groq, Mistral, OpenRouter, a LiteLLM proxy…), so a
 *    fallback is a config entry, not code.
 *  - Primary → fallback chain per tier; LARGE falls back to SMALL before failing.
 *  - Per-call timeout, one retry on retryable errors (408/429/5xx/network) with jitter.
 *  - Cost accounting per call, so metrics/alerts can speak in money.
 *
 * `fetch`, `sleep` and `now` are injectable for tests.
 */
export interface ChatRequest {
  system: string;
  user: string;
  maxTokens: number;
  temperature?: number;
}

export interface ChatResult {
  content: string;
  tokensIn: number;
  tokensOut: number;
  provider: string;
  model: string;
  latencyMs: number;
  fallbackUsed: boolean;
  attempts: number;
  costMicros: number;
}

export class ProviderError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly retryable: boolean = false,
    public readonly provider?: string,
  ) {
    super(message);
    this.name = "ProviderError";
  }
}

export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

export interface ModelGatewayOptions {
  fetchImpl?: FetchLike;
  timeoutMs?: number;
  maxAttemptsPerProvider?: number;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

export class ModelGateway {
  private readonly fetchImpl: FetchLike;
  private readonly timeoutMs: number;
  private readonly maxAttempts: number;
  private readonly now: () => number;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(
    private readonly routing: ModelRoutingConfig,
    opts: ModelGatewayOptions = {},
  ) {
    this.fetchImpl = opts.fetchImpl ?? ((input, init) => fetch(input, init));
    this.timeoutMs = opts.timeoutMs ?? MODEL_CALL.timeoutMs;
    this.maxAttempts = opts.maxAttemptsPerProvider ?? MODEL_CALL.maxAttemptsPerProvider;
    this.now = opts.now ?? Date.now;
    this.sleep = opts.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  }

  async complete(tier: "SMALL_MODEL" | "LARGE_MODEL", req: ChatRequest): Promise<ChatResult> {
    const chain = tier === "LARGE_MODEL" ? [...this.routing.large, ...this.routing.small] : this.routing.small;
    if (chain.length === 0) throw new ProviderError("no providers configured", undefined, false);

    let lastError: unknown;
    let attempts = 0;
    for (let i = 0; i < chain.length; i++) {
      const provider = chain[i];
      for (let attempt = 0; attempt < this.maxAttempts; attempt++) {
        attempts++;
        try {
          const result = await this.callOnce(provider, req);
          return { ...result, fallbackUsed: i > 0, attempts };
        } catch (err) {
          lastError = err;
          const retryable = err instanceof ProviderError && err.retryable;
          if (!retryable) break; // move to the next provider immediately
          if (attempt + 1 < this.maxAttempts) await this.sleep(150 + Math.floor(Math.random() * 250));
        }
      }
    }
    const msg = lastError instanceof Error ? lastError.message : String(lastError);
    throw new ProviderError(`all providers failed: ${msg}`, undefined, false);
  }

  private async callOnce(p: ProviderConfig, req: ChatRequest): Promise<Omit<ChatResult, "fallbackUsed" | "attempts">> {
    const started = this.now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const body: Record<string, unknown> = {
        model: p.model,
        messages: [
          { role: "system", content: req.system },
          { role: "user", content: req.user },
        ],
        temperature: req.temperature ?? 0,
        max_tokens: req.maxTokens,
      };
      if (p.jsonMode) body.response_format = { type: "json_object" };

      let res: Response;
      try {
        res = await this.fetchImpl(`${p.baseUrl.replace(/\/+$/, "")}/chat/completions`, {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${p.apiKey}` },
          body: JSON.stringify(body),
          signal: controller.signal,
        });
      } catch (err) {
        const aborted = err instanceof Error && err.name === "AbortError";
        throw new ProviderError(aborted ? `${p.name} timed out` : `${p.name} network error: ${String(err)}`, undefined, true, p.name);
      }

      if (!res.ok) {
        const retryable = res.status === 408 || res.status === 429 || res.status >= 500;
        throw new ProviderError(`${p.name} HTTP ${res.status}`, res.status, retryable, p.name);
      }

      const json = (await res.json()) as {
        choices?: { message?: { content?: string | null } }[];
        usage?: { prompt_tokens?: number; completion_tokens?: number };
      };
      const content = json.choices?.[0]?.message?.content ?? "";
      if (!content) throw new ProviderError(`${p.name} returned empty content`, res.status, true, p.name);

      const tokensIn = json.usage?.prompt_tokens ?? 0;
      const tokensOut = json.usage?.completion_tokens ?? 0;
      return {
        content,
        tokensIn,
        tokensOut,
        provider: p.name,
        model: p.model,
        latencyMs: this.now() - started,
        costMicros: costMicros(p, tokensIn, tokensOut),
      };
    } finally {
      clearTimeout(timer);
    }
  }
}

/** USD/1M tokens × tokens → micro-dollars (1e-6 USD). */
export function costMicros(p: ProviderConfig, tokensIn: number, tokensOut: number): number {
  return Math.round(tokensIn * p.inputPricePerMTokUsd + tokensOut * p.outputPricePerMTokUsd);
}

/** Models sometimes wrap JSON in prose or code fences; be forgiving but strict about the result. */
export function extractJson(content: string): unknown {
  const trimmed = content.trim();
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
  const candidate = fenced ? fenced[1] : trimmed;
  try {
    return JSON.parse(candidate);
  } catch {
    const start = candidate.indexOf("{");
    const end = candidate.lastIndexOf("}");
    if (start >= 0 && end > start) {
      return JSON.parse(candidate.slice(start, end + 1));
    }
    throw new SyntaxError("model output is not JSON");
  }
}
