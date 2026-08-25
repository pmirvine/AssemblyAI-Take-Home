#!/usr/bin/env python3
"""
iTranslate device demo — streaming STT + translation on one AssemblyAI connection.

Simulates the iTranslate handheld device: it captures audio (from a WAV file
at real-time pace, or from a microphone), streams it to AssemblyAI's
Universal-Streaming API over a WebSocket, prints live partial transcripts,
and speaks/prints a translation of each finished utterance.

Translation is done by AssemblyAI too: the connection carries an
`llm_gateway` configuration, so the service translates every finished turn
server-side and delivers the translation back on the SAME WebSocket as an
LLM Gateway response message. One vendor, one API key, one connection.

The device has no GPU, so all inference happens in AssemblyAI's cloud —
the device only captures PCM audio and speaks the translated reply.

Usage:
    export ASSEMBLYAI_API_KEY=...          # direct API-key auth (server-side)
    python device_demo.py --file sample.wav --target-lang Spanish

    # Device-style auth: mint a short-lived token from the token server
    # (see token_server.ts) instead of shipping the API key on the device.
    python device_demo.py --file sample.wav --token-url http://localhost:8787/token

    # Live microphone input (requires `pip install sounddevice`)
    python device_demo.py --mic --target-lang Spanish

    # Boost accuracy for domain terms the model wouldn't otherwise expect
    python device_demo.py --file sample.wav --keyterm "iTranslate" --keyterm "Pocketalk"

Optional: on macOS, --speak reads translations aloud via `say`.
"""

import argparse
import asyncio
import json
import os
import platform
import sys
import time
import urllib.parse
import urllib.request
import wave

import websockets  # pip install "websockets>=13"

STREAMING_ENDPOINT = "wss://streaming.assemblyai.com/v3/ws"
CHUNK_MS = 50  # v3 accepts 50ms-1000ms of audio per message; 50ms = lowest latency

# The model AssemblyAI's LLM Gateway uses for the per-turn translation. This
# is the model their real-time translation guide demonstrates; the gateway
# exposes 20+ models (Claude, GPT, Gemini, ...) behind the same config.
LLM_GATEWAY_MODEL = "gemini-2.5-flash-lite"


def llm_gateway_config(target_lang: str) -> dict:
    """Per-turn translation config, embedded in the streaming connection.

    The `{{turn}}` placeholder is filled in server-side with each finished
    turn's transcript, and the gateway's reply arrives on the same WebSocket
    as an LLM Gateway response message.
    """
    prompt = (
        f"Translate the following text into {target_lang}. "
        "Do not write a preamble. Just return the translated text.\n\n"
        # NOTE: this line must stay a plain (non-f) string so the literal
        # {{turn}} placeholder survives for the server to substitute.
        "Text: {{turn}}"
    )
    return {
        "model": LLM_GATEWAY_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 1000,
    }


async def speak(text: str, enabled: bool):
    """TTS stage. On the real device this would be a cloud TTS voice; the
    demo uses macOS `say` so the round trip is audible end-to-end."""
    if not enabled:
        print(f"    [tts] (muted) {text}")
        return
    if platform.system() == "Darwin":
        proc = await asyncio.create_subprocess_exec("say", text)
        await proc.wait()
    else:
        print(f"    [tts] no local TTS on this platform; would speak: {text}")


# ---------------------------------------------------------------------------
# Audio sources (the "device microphone")
# ---------------------------------------------------------------------------

def wav_source(path: str):
    """Yield (sample_rate, chunk_bytes) from a WAV file, paced in real time
    to mimic a live device microphone."""
    wf = wave.open(path, "rb")
    if wf.getnchannels() != 1 or wf.getsampwidth() != 2:
        sys.exit("The demo expects a mono, 16-bit PCM WAV file.")
    rate = wf.getframerate()
    frames_per_chunk = int(rate * CHUNK_MS / 1000)

    async def chunks():
        start = time.monotonic()
        i = 0
        while True:
            data = wf.readframes(frames_per_chunk)
            if not data:
                break
            yield data
            i += 1
            # Pace against wall-clock so the stream behaves like live speech.
            target = start + i * (CHUNK_MS / 1000)
            delay = target - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)

    return rate, chunks


def mic_source():
    """Yield (sample_rate, chunk_bytes) from the default microphone."""
    import queue

    import sounddevice as sd  # pip install sounddevice

    rate = 16000
    frames_per_chunk = int(rate * CHUNK_MS / 1000)
    q: "queue.Queue[bytes]" = queue.Queue()

    def callback(indata, _frames, _time, _status):
        q.put(bytes(indata))

    stream = sd.RawInputStream(
        samplerate=rate, channels=1, dtype="int16",
        blocksize=frames_per_chunk, callback=callback,
    )
    stream.start()

    async def chunks():
        print("Microphone live — speak now (Ctrl+C to stop).")
        while True:
            yield await asyncio.to_thread(q.get)

    return rate, chunks


# ---------------------------------------------------------------------------
# AssemblyAI Universal-Streaming session
# ---------------------------------------------------------------------------

def build_ws_url(sample_rate: int, keyterms: list[str], token: str | None,
                 target_lang: str) -> str:
    params = {
        "sample_rate": sample_rate,
        # The demo streams raw PCM. On cellular, the device should send
        # `encoding=opus` (actual Opus packets) instead — ~10x less bandwidth
        # for near-identical accuracy. See the README's accuracy notes.
        "encoding": "pcm_s16le",
        # One model for all supported languages, with mid-sentence
        # code-switching — the user just talks; no language toggle needed.
        "speech_model": "universal-streaming-multilingual",
        # Punctuated/cased finals, ready for the translation stage.
        "format_turns": "true",
        # AssemblyAI translates each finished turn server-side and sends the
        # result back on this same connection — no second vendor or key.
        "llm_gateway": json.dumps(llm_gateway_config(target_lang)),
    }
    if keyterms:
        params["keyterms_prompt"] = json.dumps(keyterms)
    if token:
        params["token"] = token
    query = urllib.parse.urlencode(params)
    return f"{STREAMING_ENDPOINT}?{query}"


def fetch_temp_token(token_url: str) -> str:
    """Ask our backend (token_server.ts) for a short-lived streaming token.
    This is how the physical device would authenticate — the AssemblyAI API
    key never leaves the backend."""
    with urllib.request.urlopen(token_url, timeout=10) as resp:
        return json.loads(resp.read())["token"]


async def run_session(args):
    if args.mic:
        sample_rate, chunks = mic_source()
    else:
        sample_rate, chunks = wav_source(args.file)

    token = fetch_temp_token(args.token_url) if args.token_url else None
    url = build_ws_url(sample_rate, args.keyterm, token, args.target_lang)

    headers = {}
    if not token:
        api_key = os.environ.get("ASSEMBLYAI_API_KEY")
        if not api_key:
            sys.exit("Set ASSEMBLYAI_API_KEY (or pass --token-url).")
        headers["Authorization"] = api_key

    print(
        "Connecting to Universal-Streaming "
        f"(translation to {args.target_lang} via LLM Gateway / {LLM_GATEWAY_MODEL})"
    )

    async with websockets.connect(url, additional_headers=headers) as ws:

        async def send_audio():
            async for chunk in chunks():
                await ws.send(chunk)
            # File finished: give the final turn and its translation a moment
            # to come back, then end the session (billing stops at Terminate).
            await asyncio.sleep(3)
            await ws.send(json.dumps({"type": "Terminate"}))

        async def receive():
            async for raw in ws:
                msg = json.loads(raw)
                msg_type = msg.get("type", "")

                if msg_type == "Begin":
                    print(f"Session started (id={msg.get('id')})\n")

                elif msg_type == "Turn":
                    transcript = msg.get("transcript", "")
                    if msg.get("end_of_turn") and msg.get("turn_is_formatted"):
                        # Finished utterance; its translation arrives next as
                        # an LLM Gateway response on this same socket.
                        print("\r" + " " * 100 + "\r", end="")
                        print(f"[heard]      {transcript}")
                    elif transcript:
                        # Immutable partials — ideal for a live device screen.
                        print(f"\r[partial]    {transcript}", end="", flush=True)

                elif msg_type.lower() == "llmgatewayresponse":
                    # The server-side translation of the finished turn.
                    choices = msg.get("data", {}).get("choices") or [{}]
                    translated = choices[0].get("message", {}).get("content", "")
                    if translated:
                        print(f"[translated] {translated}")
                        await speak(translated, args.speak)
                        print()

                elif msg_type == "Termination":
                    print(
                        f"Session ended: {msg.get('audio_duration_seconds', 0):.1f}s "
                        f"of audio in {msg.get('session_duration_seconds', 0):.1f}s"
                    )

        sender = asyncio.create_task(send_audio())
        try:
            await receive()
        finally:
            sender.cancel()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--file", help="mono 16-bit PCM WAV file to stream")
    source.add_argument("--mic", action="store_true", help="stream from microphone")
    parser.add_argument("--target-lang", default="Spanish",
                        help="language AssemblyAI translates finished turns into "
                             "(default: Spanish)")
    parser.add_argument("--keyterm", action="append", default=[],
                        help="domain term to boost (repeatable)")
    parser.add_argument("--token-url", default=None,
                        help="mint auth from the token server instead of the API key")
    parser.add_argument("--speak", action="store_true",
                        help="speak translations aloud (macOS `say`)")
    args = parser.parse_args()

    try:
        asyncio.run(run_session(args))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
