const crypto = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const dgram = require("node:dgram");
const os = require("node:os");
const path = require("node:path");

const PORT = Number(process.env.PORT || 8787);
const DISCOVERY_PORT = Number(process.env.DISCOVERY_PORT || 8788);
const DISCOVERY_MESSAGE = "POCKETLENS_DISCOVER_V1";
const MAX_FRAME_BYTES = 32 * 1024 * 1024;
const deviceId = crypto.randomUUID();
const page = fs.readFileSync(path.join(__dirname, "public", "index.html"));

let activeUpload = null;
let latestFrame = null;
let lastFrameAt = 0;
let frameSequence = 0;
let activePhone = null;
let httpPort = PORT;
const frameStreamClients = new Set();

function writeFrameToStream(client, frame) {
  if (client.closed) return;
  if (client.draining) {
    client.pendingFrame = frame;
    return;
  }

  const header = Buffer.allocUnsafe(4);
  header.writeUInt32BE(frame.length, 0);
  const headerReady = client.response.write(header);
  const frameReady = client.response.write(frame);
  if (!headerReady || !frameReady) client.draining = true;
}

function publishFrame(frame) {
  for (const client of frameStreamClients) {
    writeFrameToStream(client, frame);
  }
}

function handleFrameStream(request, response) {
  const remoteAddress = request.socket.remoteAddress;
  if (!["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(remoteAddress)) {
    send(response, 403, "text/plain; charset=utf-8", "Local access only.");
    return;
  }
  if (!activeUpload || activeUpload.destroyed || !latestFrame) {
    send(response, 503, "text/plain; charset=utf-8", "No live camera stream is available.");
    return;
  }

  const client = {
    response,
    draining: false,
    pendingFrame: null,
    closed: false
  };
  frameStreamClients.add(client);
  response.writeHead(200, {
    "Content-Type": "application/x-pocketlens-frame-stream",
    "Cache-Control": "no-store, no-cache, must-revalidate",
    "Connection": "keep-alive",
    "X-Content-Type-Options": "nosniff"
  });
  response.on("drain", () => {
    client.draining = false;
    const pendingFrame = client.pendingFrame;
    client.pendingFrame = null;
    if (pendingFrame) writeFrameToStream(client, pendingFrame);
  });
  response.on("close", () => {
    client.closed = true;
    client.pendingFrame = null;
    frameStreamClients.delete(client);
  });
  writeFrameToStream(client, latestFrame);
}

function send(response, status, contentType, body) {
  response.writeHead(status, {
    "Content-Type": contentType,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  response.end(body);
}

function handleUpload(request, response) {
  if (activeUpload && !activeUpload.destroyed) {
    send(response, 409, "text/plain; charset=utf-8", "A phone is already connected.");
    return;
  }

  activeUpload = request;
  activePhone = request.socket.remoteAddress || null;
  latestFrame = null;
  lastFrameAt = 0;
  const lengthHeader = Buffer.allocUnsafe(4);
  let headerBytes = 0;
  let frame = null;
  let frameBytes = 0;

  request.on("data", (chunk) => {
    let offset = 0;
    while (offset < chunk.length) {
      if (frame === null) {
        const copied = Math.min(lengthHeader.length - headerBytes, chunk.length - offset);
        chunk.copy(lengthHeader, headerBytes, offset, offset + copied);
        headerBytes += copied;
        offset += copied;
        if (headerBytes < lengthHeader.length) return;

        const frameLength = lengthHeader.readUInt32BE(0);
        if (frameLength < 4 || frameLength > MAX_FRAME_BYTES) {
          request.destroy(new Error("Invalid frame size."));
          return;
        }
        frame = Buffer.allocUnsafe(frameLength);
        frameBytes = 0;
        headerBytes = 0;
      }

      const copied = Math.min(frame.length - frameBytes, chunk.length - offset);
      chunk.copy(frame, frameBytes, offset, offset + copied);
      frameBytes += copied;
      offset += copied;
      if (frameBytes === frame.length) {
        latestFrame = frame;
        frame = null;
        lastFrameAt = Date.now();
        frameSequence += 1;
        publishFrame(latestFrame);
      }
    }
  });

  const disconnect = () => {
    if (activeUpload === request) {
      activeUpload = null;
      activePhone = null;
    }
  };
  request.on("aborted", disconnect);
  request.on("close", () => {
    if (!request.complete) {
      disconnect();
      for (const client of frameStreamClients) client.response.end();
    }
  });
  request.on("end", () => {
    disconnect();
    for (const client of frameStreamClients) client.response.end();
    if (!response.headersSent) send(response, 200, "text/plain; charset=utf-8", "Disconnected.");
  });
  request.on("error", disconnect);
}

function handleMjpeg(response) {
  response.writeHead(200, {
    "Content-Type": "multipart/x-mixed-replace; boundary=pocketlens",
    "Cache-Control": "no-store, no-cache, must-revalidate",
    "Pragma": "no-cache",
    "Connection": "keep-alive",
    "X-Content-Type-Options": "nosniff"
  });

  let sentSequence = -1;
  const timer = setInterval(() => {
    if (response.destroyed || !latestFrame || frameSequence === sentSequence) return;
    sentSequence = frameSequence;
    response.write(
      `--pocketlens\r\nContent-Type: image/jpeg\r\nContent-Length: ${latestFrame.length}\r\n\r\n`
    );
    response.write(latestFrame);
    response.write("\r\n");
  }, 50);
  response.on("close", () => clearInterval(timer));
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);

  if (request.method === "GET" && url.pathname === "/health") {
    send(response, 200, "application/json; charset=utf-8", JSON.stringify({ ok: true }));
    return;
  }
  if (request.method === "POST" && url.pathname === "/ingest") {
    handleUpload(request, response);
    return;
  }
  if (request.method === "GET" && url.pathname === "/stream.mjpg") {
    handleMjpeg(response);
    return;
  }
  if (request.method === "GET" && url.pathname === "/frame.jpg") {
    const remoteAddress = request.socket.remoteAddress;
    if (!["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(remoteAddress)) {
      send(response, 403, "text/plain; charset=utf-8", "Local access only.");
      return;
    }
    if (!latestFrame) {
      send(response, 503, "text/plain; charset=utf-8", "No camera frame is available.");
      return;
    }
    response.writeHead(200, {
      "Content-Type": "image/jpeg",
      "Content-Length": latestFrame.length,
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff"
    });
    response.end(latestFrame);
    return;
  }
  if (request.method === "GET" && url.pathname === "/frames.raw") {
    handleFrameStream(request, response);
    return;
  }
  if (request.method === "GET" && url.pathname === "/status") {
    send(response, 200, "application/json; charset=utf-8", JSON.stringify({
      connected: Boolean(activeUpload && !activeUpload.destroyed),
      phone: activePhone,
      hasFrame: Boolean(latestFrame),
      lastFrameAgeMs: lastFrameAt ? Date.now() - lastFrameAt : null
    }));
    return;
  }
  if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/obs")) {
    send(response, 200, "text/html; charset=utf-8", page);
    return;
  }
  send(response, 404, "text/plain; charset=utf-8", "Not found.");
});

function getNetworkAddresses() {
  return Object.values(os.networkInterfaces())
    .flatMap((interfaces) => interfaces || [])
    .filter((item) => item.family === "IPv4" && !item.internal && !item.address.startsWith("169.254."))
    .map((item) => item.address);
}

function startServer(port = PORT, discoveryPort = DISCOVERY_PORT) {
  return new Promise((resolve, reject) => {
    const onError = (error) => reject(error);
    server.once("error", onError);
    server.listen(port, "0.0.0.0", () => {
      server.off("error", onError);
      httpPort = server.address().port;

      const discovery = dgram.createSocket("udp4");
      let discoveryReady = false;
      discovery.on("error", (error) => {
        if (!discoveryReady) {
          server.close();
          reject(error);
          return;
        }
        console.error("PocketLens network discovery error:", error.message);
      });
      discovery.on("message", (message, remote) => {
        if (message.toString("utf8").trim() !== DISCOVERY_MESSAGE) return;
        const response = Buffer.from(JSON.stringify({
          service: "pocketlens",
          deviceId,
          name: os.hostname(),
          port: httpPort
        }));
        discovery.send(response, remote.port, remote.address);
      });
      discovery.bind(discoveryPort, "0.0.0.0", () => {
        discoveryReady = true;
        discovery.setBroadcast(true);
        resolve({
          server,
          discovery,
          deviceId,
          name: os.hostname(),
          port: httpPort,
          discoveryPort,
          addresses: getNetworkAddresses()
        });
      });
    });
  });
}

if (require.main === module) {
  startServer().then(({ port, discoveryPort, addresses }) => {
    console.log(`PocketLens PC receiver listening on port ${port}`);
    console.log(`Phone discovery listening on UDP port ${discoveryPort}`);
    for (const address of addresses) console.log(`Local network: ${address}`);
    console.log("Use only a trusted private network; discovered phones can connect without a pairing code.");
  }).catch((error) => {
    console.error("Could not start PocketLens PC receiver:", error);
    process.exitCode = 1;
  });
}

module.exports = { startServer, DISCOVERY_MESSAGE, DISCOVERY_PORT };
