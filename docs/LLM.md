# AI assistant — architecture & the edge-vs-API decision

The coach is fully functional **without** any LLM — the engine is deterministic
and unit-tested. The assistant is an optional **natural-language + voice layer**
on top: it turns "what do I throw, I'm missing 条?" into engine calls and explains
the result.

## The core principle: the engine is the authority

```
voice / text ─▶ LlmClient ─▶ Claude (orchestrates) ─┐
                                                     │ tool calls
                                  engine Assistant ◀─┘  (recommend_discard, score_hand)
                                       │
                                  Advisor / ScoreService   ← exact, tested
                                       │
                                  reply text ─▶ UI
```

The model **must** call the engine for any shanten / ukeire / discard / score
claim — it never computes mahjong math itself (`engine/Assistant.SYSTEM_PROMPT`
enforces this; the tools are unit-tested in `Tests.kt`). So the LLM can be wrong
about phrasing, never about the numbers.

## Edge or API?

**API-first (Claude), with edge as a future offline fallback.** Reasoning:

| | Claude API (default) | On-device edge (1–4B) |
|---|---|---|
| Tool-calling reliability | High | Flaky at this size |
| Vision (read tiles from a photo) | Yes — multimodal | Needs a separate model |
| Explanation quality | High | Mediocre |
| Offline at the table | ✗ | ✓ |
| Per-use cost / privacy | Per-call; data leaves device | Free; private |
| Download | None | 1–2 GB |

Because the engine already does the hard math, the LLM only needs to be good at
**understanding the request and calling tools** — where the API model is far
stronger, and where it also unlocks vision (a path to tile recognition without a
custom CV model). Edge wins on offline/privacy, so it stays a pluggable option.

The backend is chosen in **Settings** and abstracted behind `LlmClient`:
- `ClaudeClient` — official Anthropic Java SDK, model `claude-opus-4-8` (configurable),
  beta tool runner wired to the engine tools.
- `EdgeLlmClient` — stub; see plan below.
- `DisabledLlm` — assistant off (default).

## Using it

1. Settings tab → **AI assistant backend → Claude API**.
2. Paste your `sk-ant-…` key, pick a model (`claude-opus-4-8` default;
   `claude-sonnet-4-6` / `claude-haiku-4-5` for speed/cost).
3. Assistant tab → type, or tap 🎤 to speak one utterance (ASR → straight to the LLM).

### ⚠️ API-key security
The key is stored on-device and used to call the API directly — fine for a
**personal build**. Do **not** distribute an APK with a baked-in key: anyone can
extract it. For distribution, proxy requests through a small backend that holds
the key and forwards to the API.

## Edge implementation plan (Phase 6)
- Load a small instruct model (Gemma / Qwen 1.5–3B) via **MediaPipe LLM Inference**
  or **llama.cpp** (`llama.android`).
- Expose the same two engine tools through the runtime's function-calling /
  grammar-constrained output.
- Run the same tool loop as `ClaudeClient`; only `EdgeLlmClient` changes — the
  engine `Assistant` layer is backend-agnostic.
- Ship/download the model lazily; flip `EdgeLlmClient.available` once present.

## Build note
The Anthropic Java SDK pulls in OkHttp + Jackson and uses some `java.time` APIs.
On `minSdk 26` this is fine; if a future minSdk drop needs it, enable
`coreLibraryDesugaring` in `app/build.gradle.kts`.
