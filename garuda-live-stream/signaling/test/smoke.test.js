"use strict";

const { after, before, test } = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const net = require("node:net");
const path = require("node:path");
const WebSocket = require("ws");

let child;
let base;
let token;

async function freePort() {
  const socket = net.createServer();
  await new Promise((resolve, reject) => socket.listen(0, "127.0.0.1", resolve).once("error", reject));
  const port = socket.address().port;
  await new Promise((resolve) => socket.close(resolve));
  return port;
}

async function waitForHealth() {
  const until = Date.now() + 10_000;
  while (Date.now() < until) {
    try {
      const response = await fetch(`${base}/healthz`);
      if (response.ok) return;
    } catch { /* Wait for the child process to bind. */ }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Signaling server did not start.");
}

function nextJson(socket, predicate = () => true) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Timed out waiting for WebSocket message")), 4000);
    const onMessage = (raw) => {
      const message = JSON.parse(raw.toString());
      if (!predicate(message)) return;
      clearTimeout(timeout);
      socket.off("message", onMessage);
      resolve(message);
    };
    socket.on("message", onMessage);
  });
}

function connectSocket(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
  });
}

before(async () => {
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  token = `test-${require("node:crypto").randomBytes(24).toString("hex")}`;
  child = spawn(process.execPath, [path.join(__dirname, "..", "server.js")], {
    cwd: path.join(__dirname, ".."),
    env: { ...process.env, PORT: String(port), PUBLIC_BASE_URL: base, PUBLISHER_TOKEN: token, MAX_VIEWERS: "1" },
    stdio: "ignore",
  });
  await waitForHealth();
});

after(async () => {
  if (!child || child.exitCode !== null) return;
  child.kill();
  await new Promise((resolve) => child.once("exit", resolve));
});

test("creates protected sessions, relays signaling, enforces the viewer cap, then revokes the link", async () => {
  const createdResponse = await fetch(`${base}/api/sessions`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ pin: "G12-47" }),
  });
  assert.equal(createdResponse.status, 201);
  const session = await createdResponse.json();
  assert.match(session.id, /^[a-f0-9]{48}$/);
  assert.equal(session.pinRequired, true);
  assert.equal(session.viewerUrl, `${base}/s/${session.id}`);
  assert.equal(session.viewerUrl.includes("G12-47"), false);
  assert.equal((await fetch(`${base}/api/config`)).status, 404);
  assert.equal((await fetch(`${base}/api/config?session=${session.id}`)).status, 401);
  assert.equal((await fetch(`${base}/api/config?session=${session.id}`, { headers: { authorization: `Bearer ${token}` } })).status, 200);

  const publisher = await connectSocket(`${base.replace("http", "ws")}/ws?session=${session.id}&role=publisher`);
  const authenticated = nextJson(publisher, (message) => message.type === "authenticated");
  publisher.send(JSON.stringify({ type: "publisher_auth", token }));
  assert.ok((await authenticated).iceServers.length);

  const wrongPinViewer = await connectSocket(`${base.replace("http", "ws")}/ws?session=${session.id}&role=viewer`);
  const rejected = nextJson(wrongPinViewer, (message) => message.type === "server_error");
  const rejectedClose = new Promise((resolve) => wrongPinViewer.once("close", resolve));
  wrongPinViewer.send(JSON.stringify({ type: "join", pin: "wrong" }));
  assert.equal((await rejected).code, "bad_pin");
  await rejectedClose;

  const viewer = await connectSocket(`${base.replace("http", "ws")}/ws?session=${session.id}&role=viewer`);
  const joinedPromise = nextJson(viewer, (message) => message.type === "joined");
  viewer.send(JSON.stringify({ type: "join", pin: "G12-47" }));
  const joined = await joinedPromise;
  assert.ok(joined.viewerId);
  assert.equal(joined.live, true);
  const viewerCount = nextJson(publisher, (message) => message.type === "viewer_count" && message.count === 1);
  await viewerCount;

  const forwardedOffer = nextJson(publisher, (message) => message.type === "signal");
  viewer.send(JSON.stringify({ type: "signal", to: "publisher", data: { type: "offer", sdp: "test-sdp" } }));
  const offer = await forwardedOffer;
  assert.equal(offer.from, joined.viewerId);
  assert.equal(offer.data.sdp, "test-sdp");

  const forwardedAnswer = nextJson(viewer, (message) => message.type === "signal");
  publisher.send(JSON.stringify({ type: "signal", to: joined.viewerId, data: { type: "answer", sdp: "answer-sdp" } }));
  const answer = await forwardedAnswer;
  assert.equal(answer.from, "publisher");
  assert.equal(answer.data.sdp, "answer-sdp");

  const fullViewer = await connectSocket(`${base.replace("http", "ws")}/ws?session=${session.id}&role=viewer`);
  const fullError = nextJson(fullViewer, (message) => message.type === "server_error");
  const fullClose = new Promise((resolve) => fullViewer.once("close", resolve));
  fullViewer.send(JSON.stringify({ type: "join", pin: "G12-47" }));
  assert.equal((await fullError).code, "viewer_limit");
  await fullClose;

  const stopped = await fetch(`${base}/api/sessions/${session.id}`, { method: "DELETE", headers: { authorization: `Bearer ${token}` } });
  assert.equal(stopped.status, 200);
  const gone = await fetch(`${base}/api/sessions/${session.id}`);
  assert.equal(gone.status, 404);
  publisher.close();
  viewer.close();
});
