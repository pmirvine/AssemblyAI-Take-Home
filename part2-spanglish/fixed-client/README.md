# Spanglish fixed client — compile-ready

Self-contained build of the corrected `Spanglish.java` (every change tagged
`FIX` / `IMPROVEMENT` in the source). All dependencies are bundled in
`lib/`, so this compiles with nothing but a JDK (11+; verified on 21).

```bash
./build.sh                                   # javac -cp "lib/*" -d out Spanglish.java
export ASSEMBLYAI_API_KEY=your_key
./run.sh                                     # java -cp "out:lib/*" com.assemblyai.Spanglish
```

Speak into the microphone; live partials print on one line, formatted turns
print on their own lines. Ctrl+C ends the session cleanly and finalizes the
`recorded_audio_*.wav` file. On Windows, swap the classpath separator:
`javac -cp "lib/*"` works as-is, run with `java -cp "out;lib/*" com.assemblyai.Spanglish`.

## Bundled libraries

| Jar | Why |
|---|---|
| `gson-2.11.0.jar` | JSON parsing of streaming messages (customer's existing choice) |
| `Java-WebSocket-1.5.7.jar` | WebSocket client (customer's existing choice) |
| `slf4j-api-2.0.13.jar` | Required by Java-WebSocket |
| `slf4j-simple-2.0.13.jar` | Runtime logger binding so SLF4J doesn't warn at startup |
