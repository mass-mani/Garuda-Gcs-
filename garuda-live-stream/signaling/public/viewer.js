"use strict";

const sessionId = location.pathname.split("/").filter(Boolean).at(-1);
const video = document.querySelector("#video");
const placeholder = document.querySelector("#video-placeholder");
const viewerMessage = document.querySelector("#viewer-message");
const livePill = document.querySelector("#live-pill");
const liveState = document.querySelector("#live-state");
const dialog = document.querySelector("#pin-dialog");
const pinForm = document.querySelector("#pin-form");
const pinInput = document.querySelector("#pin");
const pinError = document.querySelector("#pin-error");

let iceServers = [];
let pinRequired = false;
let pinValue = "";
let ws = null;
let pc = null;
let remoteCandidates = [];
let retryTimer = null;
let reconnectDelay = 1500;
let authenticated = false;
let connectWanted = false;

function setStatus(text, live = false) {
  liveState.textContent = text;
  livePill.classList.toggle("live", live);
  livePill.classList.toggle("waiting", !live);
  viewerMessage.textContent = text === "LIVE" ? "Connecting video…" : text;
  document.querySelector("#video-tag").hidden = !live;
}

function showPin() {
  if (!dialog.open) dialog.showModal();
  pinInput.focus();
}

function openSocket() {
  if (!connectWanted || !sessionId) return;
  clearTimeout(retryTimer);
  if (ws && ws.readyState < WebSocket.CLOSING) return;
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  ws = new WebSocket(`${protocol}//${location.host}/ws?session=${encodeURIComponent(sessionId)}&role=viewer`);
  ws.addEventListener("open", () => {
    ws.send(JSON.stringify({ type: "join", pin: pinValue }));
  });
  ws.addEventListener("message", async (event) => {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === "session_ended") {
      connectWanted = false; closePeer(); setStatus("SESSION ENDED"); ws.close(); return;
    }
    if (message.type === "joined") {
      iceServers = Array.isArray(message.iceServers) ? message.iceServers : [];
      authenticated = true;
      reconnectDelay = 1500;
      dialog.close();
      setStatus(message.live ? "CONNECTING VIDEO" : "WAITING FOR G12");
      if (message.live) await negotiate();
    } else if (message.type === "publisher_ready") {
      closePeer();
      setStatus("CONNECTING VIDEO");
      await negotiate();
    } else if (message.type === "publisher_offline") {
      closePeer();
      setStatus("TRANSMITTER RECONNECTING");
    } else if (message.type === "viewer_count") {
      document.querySelector("#viewer-count").textContent = String(message.count);
    } else if (message.type === "server_error") {
      if (message.code === "bad_pin") {
        pinError.textContent = message.message;
        authenticated = false;
        connectWanted = false;
        showPin();
        setStatus("PIN REQUIRED");
      } else {
        setStatus(message.message || "SESSION ERROR");
      }
    } else if (message.type === "signal") {
      await handleSignal(message.data);
    }
  });
  ws.addEventListener("close", (event) => {
    closePeer();
    if ([1000, 4008, 4029].includes(event.code)) {
      connectWanted = false;
      authenticated = false;
      setStatus("SESSION ENDED");
      return;
    }
    if (connectWanted) {
      setStatus("RECONNECTING");
      retryTimer = setTimeout(() => {
        reconnectDelay = Math.min(8000, reconnectDelay * 1.6);
        openSocket();
      }, reconnectDelay);
    }
  });
  ws.addEventListener("error", () => setStatus("SIGNALING CONNECTION LOST"));
}

async function negotiate() {
  if (!connectWanted || !ws || ws.readyState !== WebSocket.OPEN || pc) return;
  try {
    pc = new RTCPeerConnection({ iceServers, bundlePolicy: "max-bundle" });
    remoteCandidates = [];
    const receiver = pc.addTransceiver("video", { direction: "recvonly" });
    const codecs = RTCRtpReceiver.getCapabilities?.("video")?.codecs || [];
    const h264 = codecs.filter(c => c.mimeType.toLowerCase() === "video/h264");
    if (h264.length && receiver.setCodecPreferences) receiver.setCodecPreferences(h264);
    pc.ontrack = (event) => {
      const [stream] = event.streams;
      if (stream) video.srcObject = stream;
      video.play().catch(() => {});
      placeholder.classList.add("hidden");
      setStatus("LIVE", true);
    };
    pc.onicecandidate = (event) => {
      if (event.candidate) sendSignal({ type: "candidate", candidate: event.candidate.candidate, sdpMid: event.candidate.sdpMid, sdpMLineIndex: event.candidate.sdpMLineIndex });
    };
    pc.onconnectionstatechange = () => {
      const state = pc?.connectionState || "closed";
      document.querySelector("#connection").textContent = `WebRTC · ${state}`;
      if (state === "connected") setStatus("LIVE", true);
      if (state === "failed" || state === "disconnected") {
        closePeer();
        setStatus("RECONNECTING VIDEO");
        setTimeout(() => negotiate(), 1800);
      }
    };
    const offer = await pc.createOffer();
    sendSignal({ type: "offer", sdp: offer.sdp });
    await pc.setLocalDescription(offer);
  } catch {
    closePeer();
    setStatus("WEBRTC SETUP FAILED");
  }
}

async function handleSignal(data) {
  if (!data || !pc) return;
  try {
    if (data.type === "answer") {
      await pc.setRemoteDescription({ type: "answer", sdp: data.sdp });
      for (const candidate of remoteCandidates) await pc.addIceCandidate(candidate);
      remoteCandidates = [];
    } else if (data.type === "candidate") {
      const candidate = new RTCIceCandidate({ candidate: data.candidate, sdpMid: data.sdpMid, sdpMLineIndex: data.sdpMLineIndex });
      if (pc.remoteDescription) await pc.addIceCandidate(candidate);
      else remoteCandidates.push(candidate);
    }
  } catch {
    // A late candidate can arrive while a network is being replaced; ICE will continue with others.
  }
}

function sendSignal(data) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "signal", to: "publisher", data }));
}

function closePeer() {
  if (pc) {
    pc.ontrack = null;
    pc.onicecandidate = null;
    pc.onconnectionstatechange = null;
    pc.close();
    pc = null;
  }
  video.srcObject = null;
  placeholder.classList.remove("hidden");
  document.querySelector("#resolution").textContent = "—";
  document.querySelector("#connection").textContent = "WebRTC · waiting";
}

async function loadSession() {
  if (!/^[a-f0-9]{48}$/.test(sessionId || "")) {
    setStatus("INVALID VIEWER LINK");
    return;
  }
  try {
    const sessionResponse = await fetch(`/api/sessions/${sessionId}`, { cache: "no-store" });
    if (!sessionResponse.ok) throw new Error("This live session has ended.");
    const session = await sessionResponse.json();
    pinRequired = Boolean(session.pinRequired);
    connectWanted = true;
    if (pinRequired) showPin();
    else openSocket();
  } catch (error) {
    setStatus(error.message || "SESSION UNAVAILABLE");
  }
}

pinForm.addEventListener("submit", (event) => {
  event.preventDefault();
  pinValue = pinInput.value;
  pinError.textContent = "";
  connectWanted = true;
  authenticated = false;
  openSocket();
});

async function sampleStats() {
  if (!pc || pc.connectionState !== "connected") return;
  try {
    const report = await pc.getStats();
    let rtt = null;
    let lost = null;
    let width = 0;
    let height = 0;
    report.forEach((stat) => {
      if (stat.type === "candidate-pair" && stat.state === "succeeded" && stat.nominated) rtt = stat.currentRoundTripTime;
      if (stat.type === "remote-inbound-rtp") lost = stat.packetsLost;
      if (stat.type === "inbound-rtp" && stat.kind === "video") { width = stat.frameWidth || width; height = stat.frameHeight || height; lost = stat.packetsLost ?? lost; }
    });
    if (width && height) document.querySelector("#resolution").textContent = `${width} × ${height}`;
    document.querySelector("#rtt").textContent = rtt == null ? "—" : `${Math.round(rtt * 1000)} ms`;
    document.querySelector("#packet-loss").textContent = lost == null ? "—" : String(lost);
  } catch { /* Browsers may omit a stats field until media starts. */ }
}

setInterval(sampleStats, 1800);
loadSession();
