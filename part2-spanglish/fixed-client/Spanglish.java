package com.assemblyai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed version of the streaming client Spanglish Inc. sent over.
 *
 * Summary of changes (each is tagged FIX or IMPROVEMENT inline):
 *
 *   FIX 1  encoding=opus -> encoding=pcm_s16le. The code captures raw 16-bit
 *          PCM from the microphone but told the API to expect Opus packets,
 *          so the service could not decode the audio. This is the root cause
 *          of "doesn't work at all".
 *   FIX 2  25ms audio chunks -> 50ms. The v3 Streaming API requires each
 *          binary message to contain between 50ms and 1000ms of audio.
 *   FIX 3  main() constructed a class named StreamingTranscription that does
 *          not exist in this file — the snippet as sent does not compile.
 *          It now constructs Spanglish.
 *
 *   IMPROVEMENT A  speech_model=universal-streaming-multilingual so mixed
 *                  English/Spanish speech (including mid-sentence
 *                  code-switching) is transcribed natively.
 *   IMPROVEMENT B  Audio is written to the WAV file incrementally instead of
 *                  being buffered in memory. The old List<byte[]> grew ~110MB
 *                  per hour, which matters for multi-hour court sessions.
 *   IMPROVEMENT C  API key comes from the ASSEMBLYAI_API_KEY environment
 *                  variable instead of being hardcoded in source.
 *   IMPROVEMENT D  The app now exits cleanly when the server closes the
 *                  connection (previously it would sit silently forever), and
 *                  cleanup is guarded so it runs exactly once.
 */
public class Spanglish {

    // Configuration
    // IMPROVEMENT C: never commit API keys to source control.
    private static final String API_KEY = System.getenv("ASSEMBLYAI_API_KEY");
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int SAMPLE_SIZE_IN_BITS = 16;

    // FIX 2: 800 frames = 50ms at 16kHz — the minimum chunk size the v3
    // Streaming API accepts (each message must carry 50ms–1000ms of audio).
    // The original 400 frames (25ms) was below that minimum.
    private static final int FRAMES_PER_BUFFER = 800;

    // FIX 1: encoding=pcm_s16le — this code sends raw signed 16-bit
    // little-endian PCM straight from the microphone. The original URL said
    // encoding=opus, which tells the API to expect Opus-encoded packets, so
    // every PCM chunk failed to decode and no transcripts came back.
    //
    // IMPROVEMENT A: universal-streaming-multilingual transcribes English and
    // Spanish in the same session, including mid-sentence code-switching —
    // exactly the courtroom-with-interpreter scenario. (Optionally restrict
    // candidate languages with the language_codes parameter, and add
    // speaker_labels=true to separate the interpreter from other speakers —
    // see the accompanying notes.)
    private static final String API_ENDPOINT = String.format(
        "wss://streaming.assemblyai.com/v3/ws"
            + "?sample_rate=%d"
            + "&encoding=pcm_s16le"
            + "&speech_model=universal-streaming-multilingual"
            + "&format_turns=true",
        SAMPLE_RATE
    );

    // Audio recording
    private TargetDataLine microphone;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean cleanupDone = new AtomicBoolean(false);
    private final Gson gson = new Gson();
    private AssemblyAIWebSocketClient wsClient;
    private Thread audioThread;

    // IMPROVEMENT D: the latch is a field so the WebSocket callbacks can
    // release it when the server ends the session — previously a server-side
    // close left the app running forever with nowhere to send audio.
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    // IMPROVEMENT B: stream audio to disk as it arrives instead of holding
    // every frame in memory for the lifetime of the session.
    private BufferedOutputStream wavOut;
    private File wavFile;
    private final AtomicLong wavBytesWritten = new AtomicLong(0);
    private final Object wavLock = new Object();

    public static void main(String[] args) {
        // FIX 3: the snippet we received constructed "StreamingTranscription",
        // a class that doesn't exist in this file, so it did not compile.
        Spanglish transcription = new Spanglish();
        transcription.run();
    }

    public void run() {
        System.out.println("Starting AssemblyAI real-time transcription...");
        System.out.println("Audio will be saved to a WAV file when the session ends.");

        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("Set the ASSEMBLYAI_API_KEY environment variable first.");
            return;
        }

        try {
            // Open the WAV file up front so audio can be appended as it is
            // captured (IMPROVEMENT B).
            openWavFile();

            // Initialize microphone
            initializeMicrophone();

            // Connect to WebSocket
            connectWebSocket();

            // Wait for Ctrl+C or for the server to end the session
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nCtrl+C received. Stopping...");
                stopRequested.set(true);
                cleanup();
            }));

            System.out.println("Speak into your microphone. Press Ctrl+C to stop.");
            shutdownLatch.await();
            cleanup();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            cleanup();
        }
    }

    private void initializeMicrophone() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(
            SAMPLE_RATE,
            SAMPLE_SIZE_IN_BITS,
            CHANNELS,
            true,   // signed
            false   // little endian
        );

        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Microphone not supported");
        }

        microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(format, FRAMES_PER_BUFFER * 2);

        System.out.println("Microphone initialized successfully.");
    }

    private void connectWebSocket() throws Exception {
        URI uri = new URI(API_ENDPOINT);
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", API_KEY);

        wsClient = new AssemblyAIWebSocketClient(uri, headers);
        wsClient.connectBlocking();
    }

    private void startAudioStreaming() {
        isRecording.set(true);
        microphone.start();

        audioThread = new Thread(() -> {
            System.out.println("Starting audio streaming...");
            byte[] buffer = new byte[FRAMES_PER_BUFFER * 2]; // 2 bytes per sample (16-bit)

            while (!stopRequested.get() && isRecording.get()) {
                try {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);

                    if (bytesRead > 0) {
                        // IMPROVEMENT B: append to the WAV file immediately
                        // instead of accumulating frames in a List — constant
                        // memory use no matter how long the session runs.
                        synchronized (wavLock) {
                            if (wavOut != null) {
                                wavOut.write(buffer, 0, bytesRead);
                                wavBytesWritten.addAndGet(bytesRead);
                            }
                        }

                        // Send to WebSocket
                        if (wsClient != null && wsClient.isOpen()) {
                            byte[] audioData = new byte[bytesRead];
                            System.arraycopy(buffer, 0, audioData, 0, bytesRead);
                            wsClient.send(audioData);
                        }
                    }
                } catch (Exception e) {
                    if (!stopRequested.get()) {
                        System.err.println("Error streaming audio: " + e.getMessage());
                    }
                    break;
                }
            }

            System.out.println("Audio streaming stopped.");
        });

        audioThread.start();
    }

    private void cleanup() {
        // IMPROVEMENT D: cleanup can be reached from the shutdown hook, the
        // WebSocket callbacks, or run() — make sure it only executes once.
        if (!cleanupDone.compareAndSet(false, true)) {
            return;
        }

        stopRequested.set(true);
        isRecording.set(false);

        // Stop microphone
        if (microphone != null) {
            if (microphone.isActive()) {
                microphone.stop();
            }
            microphone.close();
        }

        // Wait for audio thread to finish
        if (audioThread != null && audioThread.isAlive()) {
            try {
                audioThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Close WebSocket
        if (wsClient != null && wsClient.isOpen()) {
            try {
                // Send termination message so the session (and billing for it)
                // ends now rather than at the inactivity timeout.
                JsonObject terminateMsg = new JsonObject();
                terminateMsg.addProperty("type", "Terminate");
                wsClient.send(gson.toJson(terminateMsg));
                Thread.sleep(500); // Give the Termination reply time to arrive
                wsClient.closeBlocking();
            } catch (Exception e) {
                System.err.println("Error closing WebSocket: " + e.getMessage());
            }
        }

        // Finalize WAV file (patch the header sizes now that they are known)
        finalizeWavFile();

        shutdownLatch.countDown();
        System.out.println("Cleanup complete. Exiting.");
    }

    // ---------------------------------------------------------------------
    // WAV file handling (IMPROVEMENT B: incremental writes, header patched
    // at the end instead of buffering the whole session in memory)
    // ---------------------------------------------------------------------

    private void openWavFile() throws IOException {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());
        wavFile = new File("recorded_audio_" + timestamp + ".wav");
        wavOut = new BufferedOutputStream(new FileOutputStream(wavFile));
        // Placeholder header — the RIFF/data chunk sizes are unknown until the
        // session ends, so write zeros now and patch them in finalizeWavFile().
        wavOut.write(buildWavHeader(0));
    }

    private void finalizeWavFile() {
        synchronized (wavLock) {
            if (wavOut == null) {
                return;
            }
            long dataSize = wavBytesWritten.get();
            try {
                wavOut.close();
                wavOut = null;

                if (dataSize == 0) {
                    System.out.println("No audio data recorded.");
                    if (!wavFile.delete()) {
                        System.err.println("Could not remove empty file: " + wavFile);
                    }
                    return;
                }

                // Patch the two size fields in the 44-byte header.
                try (RandomAccessFile raf = new RandomAccessFile(wavFile, "rw")) {
                    raf.write(buildWavHeader((int) dataSize));
                }

                double durationSeconds = (double) dataSize / (SAMPLE_RATE * CHANNELS * 2);
                System.out.printf("Audio saved to: %s%n", wavFile.getName());
                System.out.printf("Duration: %.2f seconds%n", durationSeconds);
            } catch (IOException e) {
                System.err.println("Error saving WAV file: " + e.getMessage());
            }
        }
    }

    private byte[] buildWavHeader(int dataSize) {
        ByteBuffer buffer = ByteBuffer.allocate(44);
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes());

        // fmt chunk
        buffer.put("fmt ".getBytes());
        buffer.putInt(16);                                   // fmt chunk size
        buffer.putShort((short) 1);                          // PCM format
        buffer.putShort((short) CHANNELS);
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(SAMPLE_RATE * CHANNELS * 2);           // byte rate
        buffer.putShort((short) (CHANNELS * 2));             // block align
        buffer.putShort((short) SAMPLE_SIZE_IN_BITS);

        // data chunk
        buffer.put("data".getBytes());
        buffer.putInt(dataSize);

        return buffer.array();
    }

    // Inner class for WebSocket client
    private class AssemblyAIWebSocketClient extends WebSocketClient {

        public AssemblyAIWebSocketClient(URI serverUri, Map<String, String> headers) {
            super(serverUri, headers);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            System.out.println("WebSocket connection opened.");
            System.out.println("Connected to: " + API_ENDPOINT);
            startAudioStreaming();
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject data = gson.fromJson(message, JsonObject.class);
                String msgType = data.get("type").getAsString();

                switch (msgType) {
                    case "Begin":
                        handleBeginMessage(data);
                        break;
                    case "Turn":
                        handleTurnMessage(data);
                        break;
                    case "Termination":
                        handleTerminationMessage(data);
                        break;
                    default:
                        // Ignore unknown message types
                        break;
                }
            } catch (Exception e) {
                System.err.println("Error handling message: " + e.getMessage());
            }
        }

        private void handleBeginMessage(JsonObject data) {
            String sessionId = data.get("id").getAsString();
            long expiresAt = data.get("expires_at").getAsLong();

            Instant instant = Instant.ofEpochSecond(expiresAt);
            String formattedTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(instant);

            System.out.printf("%nSession began: ID=%s, ExpiresAt=%s%n", sessionId, formattedTime);
        }

        private void handleTurnMessage(JsonObject data) {
            String transcript = data.has("transcript")
                ? data.get("transcript").getAsString() : "";
            boolean formatted = data.has("turn_is_formatted")
                && data.get("turn_is_formatted").getAsBoolean();

            if (formatted) {
                // Clear line and print formatted transcript
                System.out.print("\r" + " ".repeat(80) + "\r");
                System.out.println(transcript);
            } else {
                // Print partial transcript on same line
                System.out.print("\r" + transcript);
            }
        }

        private void handleTerminationMessage(JsonObject data) {
            double audioDuration = data.has("audio_duration_seconds")
                ? data.get("audio_duration_seconds").getAsDouble() : 0.0;
            double sessionDuration = data.has("session_duration_seconds")
                ? data.get("session_duration_seconds").getAsDouble() : 0.0;

            System.out.printf("%nSession Terminated: Audio Duration=%.2fs, Session Duration=%.2fs%n",
                audioDuration, sessionDuration);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            System.out.printf("%nWebSocket Disconnected: Status=%d, Msg=%s%n", code, reason);
            stopRequested.set(true);
            // IMPROVEMENT D: unblock main() so the app shuts down instead of
            // hanging after a server-side close (e.g. an error or timeout).
            shutdownLatch.countDown();
        }

        @Override
        public void onError(Exception ex) {
            System.err.println("\nWebSocket Error: " + ex.getMessage());
            stopRequested.set(true);
            shutdownLatch.countDown();
        }
    }
}
