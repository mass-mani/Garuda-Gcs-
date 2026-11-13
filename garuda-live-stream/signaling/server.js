"use strict";

const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { isIP } = require("node:net");
const { promisify } = require("node:util");
const { WebSocketServer, WebSocket } = require("ws");
const scrypt = promisify(crypto.scrypt);

function setting(name, fallback, min, max) {
  const value = Number(process.env[name] || fallback);
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${name} must be an integer between ${min} and ${max}`);
  return value;
}

const PORT = setting("PORT", 8080, 1, 65535);
const BASE_URL = (process.env.PUBLIC_BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const PUBLIC_ORIGIN = new URL(BASE_URL).origin;
const PUBLISHER_TOKEN = process.env.PUBLISHER_TOKEN || "";
const TTL_HOURS = setting("SESSION_TTL_HOURS", 6, 1, 12);
const MAX_VIEWERS = setting("MAX_VIEWERS", 3, 1, 16);
const PUBLISHER_GRACE_MS = 90_000;
const PUBLIC_DIR = path.join(__dirname, "public");
const sessions = new Map();
const apiLimits = new Map();
const pinLimits = new Map();
const socketLimits = new Map();
const STUN_URLS = (process.env.STUN_URLS || "stun:stun.l.google.com:19302").split(",").map((v) => v.trim()).filter(Boolean);

function clientIp(req) {
  // Enable only behind one trusted proxy, with the Node port inaccessible publicly.
  if (process.env.TRUST_PROXY === "1") {
    const forwarded = String(req.headers["x-forwarded-for"] || "").split(",").at(-1).trim();
    if (isIP(forwarded)) return forwarded;
  }
  return req.socket.remoteAddress || "unknown";
}

function allowedOrigin(req) {
  return !req.headers.origin || req.headers.origin === PUBLIC_ORIGIN;
}

function secureEqual(a, b) {
  const aa = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}

function limit(map, key, max, periodMs) {
  const now = Date.now();
  let value = map.get(key);
  if (!value || now - value.start >= periodMs) value = { start: now, count: 0 };
  value.count++;
  map.set(key, value);
  return value.count <= max;
}

function sendJson(res, code, value) {
  const body = JSON.stringify(value);
  res.writeHead(code, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
  });
  res.end(body);
}

function sendSocket(ws, value) {
  if (ws.readyState !== WebSocket.OPEN) return;
  if (ws.bufferedAmount > 1024 * 1024) return ws.terminate();
  ws.send(JSON.stringify(value));
}

function roomCount(room) { return room.viewers.size; }
function broadcastCount(room) {
  const value = { type: "viewer_count", count: roomCount(room) };
  if (room.publisher) sendSocket(room.publisher, value);
  for (const viewer of room.viewers.values()) sendSocket(viewer, value);
}

function makeIceServers(room) {
  const iceServers = STUN_URLS.map((url) => ({ urls: url }));
  const urls = (process.env.TURN_URLS || "").split(",").map((v) => v.trim()).filter(Boolean);
  const secret = process.env.TURN_SECRET || "";
  if (urls.length && secret) {
    // Credentials remain usable for ICE reconnects throughout this temporary room.
    const username = `${Math.ceil(room.expiresAt / 1000)}:${room.id}`;
    const credential = crypto.createHmac("sha1", secret).update(username).digest("base64");
    iceServers.push({ urls, username, credential });
  }
  return iceServers;
}

function endSession(room, reason) {
  if (sessions.get(room.id) !== room) return;
  sessions.delete(room.id); // Revoke before processing any queued socket messages.
  for (const ws of wss.clients) {
    if (ws.context.room !== room) continue;
    sendSocket(ws, { type: "session_ended", message: reason });
    ws.close(1000, reason);
  }
}

function sessionIsActive(room, now = Date.now()) {
  return room && sessions.get(room.id) === room && room.expiresAt > now &&
    (room.publisher || now - room.offlineSince < PUBLISHER_GRACE_MS);
}

function readJson(req, maxBytes = 16 * 1024) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (Buffer.byteLength(data) > maxBytes) {
        reject(new Error("Request too large"));
        req.destroy();
      }
    });
    req.on("end", () => {
      try { resolve(data ? JSON.parse(data) : {}); }
      catch { reject(new Error("Invalid JSON")); }
    });
    req.on("error", reject);
  });
}

function hasPublisherAuth(req) {
  if (!PUBLISHER_TOKEN) return false;
  return secureEqual(req.headers.authorization || "", `Bearer ${PUBLISHER_TOKEN}`);
}

async function route(req, res) {
  const url = new URL(req.url, "http://localhost");
  const ip = clientIp(req);
  if (!allowedOrigin(req)) return sendJson(res, 403, { error: "Origin is not allowed." });
  if (req.method === "GET" && url.pathname === "/healthz") return sendJson(res, 200, { ok: true });
  if (req.method === "GET" && url.pathname === "/api/config") {
    const room = sessions.get(url.searchParams.get("session"));
    if (!sessionIsActive(room)) return sendJson(res, 404, { error: "This live session has ended." });
    // Viewers get ICE configuration only in the authenticated joined message.
    if (!hasPublisherAuth(req)) return sendJson(res, 401, { error: "Publisher authentication required." });
    if (!limit(apiLimits, `config:${ip}`, 120, 60_000)) return sendJson(res, 429, { error: "Too many configuration requests." });
    return sendJson(res, 200, { iceServers: makeIceServers(room) });
  }

  if (req.method === "POST" && url.pathname === "/api/sessions") {
    if (!limit(apiLimits, `create:${ip}`, 20, 60_000)) return sendJson(res, 429, { error: "Too many session requests." });
    if (!PUBLISHER_TOKEN) return sendJson(res, 503, { error: "Server is not configured with a publisher token." });
    if (!hasPublisherAuth(req)) return sendJson(res, 401, { error: "Publisher authentication failed." });
    const publicUrl = new URL(BASE_URL);
    if (publicUrl.protocol !== "https:" && !["localhost", "127.0.0.1", "::1"].includes(publicUrl.hostname)) {
      return sendJson(res, 503, { error: "Configure PUBLIC_BASE_URL with HTTPS before creating public sessions." });
    }
    if (sessions.size >= 1000) return sendJson(res, 503, { error: "The signaling server has reached its active session limit." });
    let body;
    try { body = await readJson(req); } catch (error) { return sendJson(res, 400, { error: error.message }); }
    if (!body || typeof body !== "object" || Array.isArray(body)) return sendJson(res, 400, { error: "Expected a JSON object." });
    const pin = typeof body.pin === "string" ? body.pin : "";
    if (pin && (pin.length < 4 || pin.length > 12)) return sendJson(res, 400, { error: "Viewer PIN must be 4 to 12 characters." });
    const id = crypto.randomBytes(24).toString("hex");
    const room = {
      id,
      expiresAt: Date.now() + TTL_HOURS * 3600_000,
      pinSalt: null,
      pinHash: null,
      publisher: null,
      offlineSince: Date.now(),
      viewers: new Map(),
    };
    // Keep the same salt used for the verifier.
    if (pin) {
      room.pinSalt = crypto.randomBytes(16);
      room.pinHash = await scrypt(pin, room.pinSalt, 32);
    }
    sessions.set(id, room);
    return sendJson(res, 201, {
      id,
      viewerUrl: `${BASE_URL}/s/${id}`,
      pinRequired: Boolean(pin),
      iceServers: makeIceServers(room),
      expiresAt: new Date(room.expiresAt).toISOString(),
    });
  }

  const sessionMatch = url.pathname.match(/^\/api\/sessions\/([a-f0-9]{48})$/);
  if (sessionMatch && req.method === "GET") {
    if (!limit(apiLimits, `status:${ip}`, 120, 60_000)) return sendJson(res, 429, { error: "Too many status requests." });
    const room = sessions.get(sessionMatch[1]);
    if (!sessionIsActive(room)) return sendJson(res, 404, { error: "This live session has ended." });
    return sendJson(res, 200, { live: Boolean(room.publisher), pinRequired: Boolean(room.pinHash), viewerCount: roomCount(room), expiresAt: new Date(room.expiresAt).toISOString() });
  }
  if (sessionMatch && req.method === "DELETE") {
    if (!hasPublisherAuth(req)) return sendJson(res, 401, { error: "Publisher authentication failed." });
    const room = sessions.get(sessionMatch[1]);
    if (!room) return sendJson(res, 404, { error: "This live session has ended." });
    endSession(room, "Live session stopped");
    return sendJson(res, 200, { stopped: true });
  }
  if (req.method === "GET" && url.pathname.startsWith("/s/")) {
    const id = url.pathname.slice(3);
    if (!/^[a-f0-9]{48}$/.test(id)) return sendJson(res, 404, { error: "Not found" });
    return sendFile(res, "viewer.html", "text/html; charset=utf-8");
  }
  if (req.method === "GET" && ["/viewer.js", "/viewer.css", "/favicon.svg"].includes(url.pathname)) {
    const name = path.basename(url.pathname);
    const type = name.endsWith(".js") ? "text/javascript; charset=utf-8" : name.endsWith(".css") ? "text/css; charset=utf-8" : "image/svg+xml";
    return sendFile(res, name, type);
  }
  if (req.method === "GET" && url.pathname === "/") return sendFile(res, "viewer.html", "text/html; charset=utf-8");
  sendJson(res, 404, { error: "Not found" });
}

function sendFile(res, name, contentType) {
  fs.readFile(path.join(PUBLIC_DIR, name), (error, content) => {
    if (error) return sendJson(res, 404, { error: "Not found" });
    res.writeHead(200, {
      "content-type": contentType,
      "content-length": content.length,
      "cache-control": name === "viewer.html" ? "no-store" : "public, max-age=3600",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
      "permissions-policy": "camera=(), microphone=()",
      "content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    });
    res.end(content);
  });
}

const server = http.createServer((req, res) => {
  Promise.resolve(route(req, res)).catch((error) => {
    if (!res.headersSent) sendJson(res, 500, { error: "Internal server error" });
    else res.destroy();
    console.error("request failed", error.message);
  });
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024, perMessageDeflate: false });
server.on("upgrade", (req, socket, head) => {
  let url;
  try { url = new URL(req.url, "http://localhost"); } catch { socket.destroy(); return; }
  const id = url.searchParams.get("session");
  const role = url.searchParams.get("role");
  const room = sessions.get(id);
  const ip = clientIp(req);
  if (!allowedOrigin(req)) {
    socket.end("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
    return;
  }
  if (!limit(socketLimits, ip, 60, 60_000)) {
    socket.write("HTTP/1.1 429 Too Many Requests\r\n\r\n");
    socket.destroy();
    return;
  }
  if (url.pathname !== "/ws" || !sessionIsActive(room) || !["publisher", "viewer"].includes(role)) {
    socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.context = { room, role, authenticated: false, authenticating: false, id: null, count: 0, window: Date.now(), ip };
    wss.emit("connection", ws, req);
  });
});

wss.on("connection", (ws) => {
  const ctx = ws.context;
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });
  ws.on("error", () => { /* Transport errors are handled by close and heartbeat. */ });
  const authTimer = setTimeout(() => ws.close(4001, "Authentication required"), 5000);
  ws.on("message", (raw) => { handleMessage(raw).catch(() => ws.close(1011, "Signaling failed")); });
  async function handleMessage(raw) {
    const now = Date.now();
    if (now - ctx.window > 10_000) { ctx.window = now; ctx.count = 0; }
    if (++ctx.count > 120) return ws.close(4008, "Rate limit exceeded");
    let message;
    try { message = JSON.parse(raw.toString()); } catch { return ws.close(4002, "Invalid message"); }
    if (!message || typeof message !== "object" || Array.isArray(message)) return ws.close(4002, "Invalid message");
    const room = ctx.room;
    if (!sessionIsActive(room)) return ws.close(1000, "Session ended");
    if (!ctx.authenticated) {
      if (ctx.authenticating) return;
      if (ctx.role === "publisher") {
        if (message.type !== "publisher_auth" || !PUBLISHER_TOKEN || !secureEqual(message.token || "", PUBLISHER_TOKEN)) {
          return ws.close(4003, "Publisher authentication failed");
        }
        if (room.publisher && room.publisher.readyState === WebSocket.OPEN) room.publisher.close(4004, "Publisher reconnected");
        ctx.authenticated = true;
        room.publisher = ws;
        room.offlineSince = null;
        clearTimeout(authTimer);
        sendSocket(ws, { type: "authenticated", iceServers: makeIceServers(room), expiresAt: new Date(room.expiresAt).toISOString(), viewerCount: roomCount(room) });
        for (const viewer of room.viewers.values()) sendSocket(viewer, { type: "publisher_ready" });
        broadcastCount(room);
        return;
      }
      if (message.type !== "join") return ws.close(4002, "Viewer join required");
      if (room.pinHash) {
        const limiterKey = `${room.id}:${ctx.ip}`;
        if (!limit(pinLimits, limiterKey, 8, 10 * 60_000)) return ws.close(4008, "Too many PIN attempts");
        const pin = typeof message.pin === "string" ? message.pin : "";
        if (pin.length < 4 || pin.length > 12) {
          sendSocket(ws, { type: "server_error", code: "bad_pin", message: "Enter a PIN of 4 to 12 characters." });
          return ws.close(4003, "PIN rejected");
        }
        if (!limit(pinLimits, `all:${ctx.ip}`, 40, 10 * 60_000)) return ws.close(4008, "Too many PIN attempts");
        ctx.authenticating = true;
        const check = await scrypt(pin, room.pinSalt, 32);
        ctx.authenticating = false;
        if (ws.readyState !== WebSocket.OPEN || !sessionIsActive(room)) return;
        if (check.length !== room.pinHash.length || !crypto.timingSafeEqual(check, room.pinHash)) {
          sendSocket(ws, { type: "server_error", code: "bad_pin", message: "That PIN is not correct." });
          return ws.close(4003, "PIN rejected");
        }
      }
      if (room.viewers.size >= MAX_VIEWERS) {
        sendSocket(ws, { type: "server_error", code: "viewer_limit", message: "This live session has reached its viewer limit." });
        return ws.close(4029, "Viewer limit reached");
      }
      ctx.authenticated = true;
      ctx.id = crypto.randomBytes(12).toString("hex");
      room.viewers.set(ctx.id, ws);
      clearTimeout(authTimer);
      sendSocket(ws, { type: "joined", viewerId: ctx.id, live: Boolean(room.publisher), pinRequired: Boolean(room.pinHash), iceServers: makeIceServers(room), expiresAt: new Date(room.expiresAt).toISOString() });
      if (room.publisher) sendSocket(room.publisher, { type: "viewer_joined", viewerId: ctx.id });
      broadcastCount(room);
      return;
    }

    if (ctx.role === "publisher" && room.publisher !== ws) return ws.close(4004, "Publisher reconnected");
    if (ctx.role === "publisher" && message.type === "publisher_stop") return endSession(room, "Live session stopped");

    if (message.type !== "signal" || !message.data || typeof message.data.type !== "string") return;
    if (!["offer", "answer", "candidate"].includes(message.data.type)) return;
    if (ctx.role === "viewer") {
      if (message.to !== "publisher" || !room.publisher) return;
      sendSocket(room.publisher, { type: "signal", from: ctx.id, data: message.data });
    } else {
      const target = room.viewers.get(String(message.to || ""));
      if (target) sendSocket(target, { type: "signal", from: "publisher", data: message.data });
    }
  }

  ws.on("close", () => {
    clearTimeout(authTimer);
    const room = ctx.room;
    if (!ctx.authenticated || sessions.get(room.id) !== room) return;
    if (ctx.role === "publisher" && room.publisher === ws) {
      room.publisher = null;
      room.offlineSince = Date.now();
      for (const viewer of room.viewers.values()) sendSocket(viewer, { type: "publisher_offline" });
      broadcastCount(room);
    } else if (ctx.role === "viewer" && room.viewers.get(ctx.id) === ws) {
      room.viewers.delete(ctx.id);
      if (room.publisher) sendSocket(room.publisher, { type: "viewer_left", viewerId: ctx.id });
      broadcastCount(room);
    }
  });
});

function sweepSessions(now = Date.now()) {
  for (const [id, room] of sessions) {
    if (room.expiresAt <= now) endSession(room, "Session expired");
    else if (!room.publisher && now - room.offlineSince >= PUBLISHER_GRACE_MS) endSession(room, "Transmitter disconnected");
  }
  for (const map of [apiLimits, pinLimits, socketLimits]) for (const [key, value] of map) if (now - value.start > 15 * 60_000) map.delete(key);
}

function heartbeat() {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}

if (require.main === module) {
  setInterval(sweepSessions, 10_000).unref();
  setInterval(heartbeat, 30_000).unref();
  server.listen(PORT, process.env.BIND_HOST || "0.0.0.0", () => {
  if (!PUBLISHER_TOKEN) console.warn("PUBLISHER_TOKEN is empty; publisher session creation is disabled.");
  console.log(`Garuda Live signaling listening on :${PORT}; public origin ${BASE_URL}`);
  });
}

module.exports = { server, wss, sessions, sweepSessions, heartbeat, clientIp, sessionIsActive };
