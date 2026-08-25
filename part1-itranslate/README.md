# Part 1 — iTranslate: Streaming STT for a handheld translator

iTranslate builds a portable, battery-powered translation device (think
Pocketalk): it hears speech, transcribes it, translates it, and speaks the
translation aloud. The device has WiFi/cellular but **no GPU**, so all model
inference must happen in the cloud. Their ask: **improve STT accuracy**.

This folder contains a working demo plus the approach I'd walk the account
executive and customer through.

```
demo/
  device_demo.py     Python "device simulator": streams audio to Universal-
                     Streaming, prints live partials, and speaks the
                     AssemblyAI-translated version of each finished utterance
  token_server.ts    TypeScript backend that mints short-lived streaming
                     tokens so the API key never ships on a device
  requirements.txt
```

Both languages were chosen deliberately — iTranslate is a Python/TypeScript
shop, so each half of the demo lands in a stack they already run.

---

## Recommended architecture

```
┌─────────────── iTranslate device ───────────────┐
│  mic ──► 16kHz mono PCM ──► (Opus on cellular)  │
│                    │                            │
│  speaker ◄── TTS audio                          │
└────────────────────┼────────────────────────────┘
          1. auth    │  2. audio out / turns in
                     ▼
   ┌──────────────────────────────────────────────┐
   │ iTranslate backend (TypeScript)              │
   │   /token  ──►  mints 60s streaming token     │
   └──────────────────────────────────────────────┘
                     │
                     ▼
   wss://streaming.assemblyai.com/v3/ws          (AssemblyAI cloud)
     • immutable partial transcripts (~300ms)  ──► live captions on screen
     • formatted final turns                   ──► shown as "heard"
     • per-turn translation (LLM Gateway,
       configured on the same connection)      ──► TTS ──► device speaker
```

- The device opens one WebSocket per conversation session and streams
  **50ms audio chunks** (the v3 minimum — smallest chunk, lowest latency).
- **Partial transcripts** drive the device screen: they're immutable in
  Universal-Streaming, so text never flickers or rewrites under the user's
  eyes.
- **Formatted final turns** (`format_turns=true`) feed the translation
  stage: punctuated, cased sentences translate dramatically better than raw
  lowercase token streams.
- **Translation happens inside AssemblyAI.** The connection carries an
  [`llm_gateway` configuration](https://www.assemblyai.com/docs/streaming/guides/apply-llm-gateway-to-streaming)
  with a translation prompt and a `{{turn}}` placeholder; the service fills
  in each finished turn, translates it server-side, and delivers the result
  on the same WebSocket ([real-time translation guide](https://www.assemblyai.com/docs/guides/real_time_translation)).
  One vendor, one API key, one connection — no second account for iTranslate
  to manage. An LLM-based translation also handles slang, half-sentences,
  and code-switched input gracefully, and the prompt can carry device
  context (formality, domain, glossary).
- TTS remains the pluggable downstream stage; the demo uses local TTS as a
  stand-in.

## Why Universal-Streaming fits this device

| Device constraint | How it's addressed |
|---|---|
| No GPU / limited compute | All inference is cloud-side; the device only captures PCM and plays audio |
| Two people, two languages | `speech_model=universal-streaming-multilingual` transcribes six languages **with mid-sentence code-switching** — no "which language am I hearing?" toggle to get wrong |
| Translation, without a second vendor | AssemblyAI's LLM Gateway is configured on the same streaming connection and translates every finished turn server-side — same key, same WebSocket |
| Conversational UX | Immutable partials in ~300ms keep the screen live while the turn completes |
| Battery + cellular budget | `encoding=opus` cuts audio bandwidth ~10x vs raw PCM with near-identical accuracy; raw `pcm_s16le` on WiFi |
| Fleet security | Devices authenticate with 60-second single-use tokens minted by iTranslate's backend (`token_server.ts`); the API key never leaves their servers |
| Fleet growth | Concurrency scales with the fleet — no per-session cap to engineer around |

## The accuracy playbook

This is the conversation the customer actually wants to have. In order of
impact:

1. **Get the audio path right first.** Most "model accuracy" complaints are
   audio-pipeline bugs: a declared encoding that doesn't match the bytes
   sent, double resampling, or dropped frames. Send 16kHz mono 16-bit PCM
   (or true Opus with `encoding=opus`), and make sure `sample_rate` matches
   the capture rate exactly.
2. **Use the multilingual model.** A translation device constantly hears
   mixed-language speech. A single model that code-switches natively beats
   any language-detection-then-switch scheme, which loses the first words
   of every language change.
3. **Boost domain vocabulary with `keyterms_prompt`.** Product names, city
   names, brand terms — the demo exposes this as `--keyterm` so it's easy
   to A/B in front of the customer.
4. **Tune turn detection for translation.** For this use case a split
   sentence is worse than a slow one — half-sentences translate badly. Bias
   toward complete turns (raise `end_of_turn_confidence_threshold` /
   `max_turn_silence`) rather than fastest endpointing.
5. **Match the acoustic profile.** A handheld held between two speakers is
   far-field audio — `voice_focus=far-field` exists for exactly this.
6. **Measure, don't guess.** Build a small eval set from real device
   recordings (their actual mic, real environments) and track WER as
   settings change. I'd offer to run this benchmark with them — it turns
   "accuracy feels bad" into a number we can move.

## Latency budget (speech → translated speech)

| Stage | Rough budget |
|---|---|
| Audio chunking (50ms frames) | 50ms |
| Network to AssemblyAI | 30–100ms (cellular) |
| First partial transcript | ~300ms from speech |
| End-of-turn detection + formatted final | driven by turn-detection settings |
| Translation (LLM Gateway, server-side) | 300–800ms |
| TTS synthesis + playback start | 200–500ms |

The perceived experience is dominated by the tail (turn end → spoken
translation), which is why partials-on-screen matters: the user sees the
device "hearing them" instantly even while translation is in flight.

## Running the demo

```bash
# 1. (optional, device-style auth) start the token server
export ASSEMBLYAI_API_KEY=your_key
npx tsx demo/token_server.ts

# 2. run the device simulator against any mono 16-bit WAV
pip install -r demo/requirements.txt
python demo/device_demo.py --file conversation.wav --target-lang Spanish

# or with device-style token auth and live mic:
python demo/device_demo.py --mic --token-url http://localhost:8787/token --speak
```

Useful flags: `--keyterm "Pocketalk"` (repeatable) to demo vocabulary
boosting, `--speak` for audible TTS on macOS, `--target-lang` for the
translation target. The AssemblyAI key is the only credential the demo
needs — transcription and translation both run through it.

## From demo to production

- **Reconnect logic**: on network loss (this is a cellular device), retry
  with exponential backoff and resume in a fresh session.
- **Terminate sessions promptly**: streaming is billed on connection-open
  time, so close the socket when the conversation ends, not on idle timeout.
- **Official SDKs**: the demo speaks the raw WebSocket protocol so every
  moving part is visible; for production, AssemblyAI's Python and
  JavaScript/TypeScript SDKs wrap this same v3 API.
- **Recorded audio**: if iTranslate ever adds a "review past conversations"
  feature, the async API's
  [Speech Understanding translation](https://www.assemblyai.com/docs/speech-understanding/translation)
  translates finished transcripts into 80+ languages in the same
  `/v2/transcript` request — again on the same key.
- **Privacy posture**: streaming supports zero data retention of audio and
  transcripts (with model-training opt-out) — a strong story for a consumer
  device that hears private conversations.
