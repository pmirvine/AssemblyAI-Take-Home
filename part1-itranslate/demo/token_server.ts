/**
 * Token server for the iTranslate device fleet.
 *
 * The handheld devices must never hold the AssemblyAI API key — a key on a
 * shippable device is a key that leaks. Instead, each device asks this
 * backend for a short-lived streaming token and connects to
 * wss://streaming.assemblyai.com/v3/ws with `?token=...`.
 *
 * In production this endpoint would sit behind the device's normal auth
 * (device certificate / signed JWT); the demo keeps it open for simplicity.
 *
 * Run:
 *   export ASSEMBLYAI_API_KEY=...
 *   npx tsx token_server.ts        # or: npm i -D tsx typescript @types/node
 *
 * Then: python device_demo.py --file sample.wav --token-url http://localhost:8787/token
 */

import http from "node:http";

const PORT = Number(process.env.PORT ?? 8787);
const API_KEY = process.env.ASSEMBLYAI_API_KEY;

// Tokens are single-use and short-lived: 60s to redeem, and the session a
// token opens is capped at 1 hour — a stolen token is nearly worthless.
const TOKEN_URL =
  "https://streaming.assemblyai.com/v3/token" +
  "?expires_in_seconds=60&max_session_duration_seconds=3600";

if (!API_KEY) {
  console.error("Set the ASSEMBLYAI_API_KEY environment variable first.");
  process.exit(1);
}

const server = http.createServer(async (req, res) => {
  if (req.method !== "GET" || !req.url?.startsWith("/token")) {
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not found" }));
    return;
  }

  try {
    const upstream = await fetch(TOKEN_URL, {
      headers: { Authorization: API_KEY },
    });

    if (!upstream.ok) {
      console.error(`Token mint failed: HTTP ${upstream.status}`);
      res.writeHead(502, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "could not mint streaming token" }));
      return;
    }

    // Pass the { token, expires_in_seconds } payload straight through.
    const body = await upstream.text();
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(body);
  } catch (err) {
    console.error("Token mint error:", err);
    res.writeHead(502, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "token service unavailable" }));
  }
});

server.listen(PORT, () => {
  console.log(`Token server listening on http://localhost:${PORT}/token`);
});
