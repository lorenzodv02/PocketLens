const assert = require("node:assert/strict");
const dgram = require("node:dgram");
const http = require("node:http");
const { test } = require("node:test");
const { startServer, DISCOVERY_MESSAGE } = require("./server");

function request(port, path, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: "127.0.0.1",
      port,
      path,
      method: options.method || "GET",
      headers: options.headers
    }, (res) => {
      if (options.firstChunk) {
        res.once("data", (chunk) => resolve({ status: res.statusCode, chunk, req }));
        return;
      }
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => {
        const body = Buffer.concat(chunks);
        resolve({ status: res.statusCode, body: options.raw ? body : body.toString("utf8") });
      });
    });
    req.on("error", reject);
    if (options.body) req.write(options.body);
    req.end();
  });
}

function openUpload(port, body) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: "127.0.0.1",
      port,
      path: "/ingest",
      method: "POST",
      headers: { "Transfer-Encoding": "chunked" }
    });
    req.on("response", (res) => res.resume());
    req.on("error", () => {});
    req.write(body);
    req.flushHeaders();
    req.once("socket", (socket) => {
      if (socket.connecting) socket.once("connect", () => resolve(req));
      else resolve(req);
    });
  });
}

function openFrameStream(port) {
  return new Promise((resolve, reject) => {
    const request = http.get({ host: "127.0.0.1", port, path: "/frames.raw" }, (response) => {
      let buffered = Buffer.alloc(0);
      const frames = [];
      const waiters = [];
      response.on("data", (chunk) => {
        buffered = Buffer.concat([buffered, chunk]);
        while (buffered.length >= 4) {
          const frameLength = buffered.readUInt32BE(0);
          if (buffered.length < frameLength + 4) break;
          const frame = buffered.subarray(4, frameLength + 4);
          buffered = buffered.subarray(frameLength + 4);
          const waiter = waiters.shift();
          if (waiter) waiter(frame);
          else frames.push(frame);
        }
      });
      response.once("error", reject);
      resolve({
        status: response.statusCode,
        nextFrame: () => frames.length
          ? Promise.resolve(frames.shift())
          : new Promise((frameResolve) => waiters.push(frameResolve)),
        close: () => request.destroy()
      });
    });
    request.once("error", (error) => {
      if (error.code !== "ECONNRESET") reject(error);
    });
  });
}

test("discovers a PC over broadcast and accepts an unpaired camera stream", async (t) => {
  const receiver = await startServer(0, 0);
  const port = receiver.port;
  const discoveryPort = receiver.discovery.address().port;
  t.after(() => {
    receiver.discovery.close();
    receiver.server.closeAllConnections();
    receiver.server.close();
  });

  const discoveryReply = await new Promise((resolve, reject) => {
    const client = dgram.createSocket("udp4");
    const timeout = setTimeout(() => {
      client.close();
      reject(new Error("No response to the PC discovery broadcast."));
    }, 2000);
    client.on("error", (error) => {
      clearTimeout(timeout);
      client.close();
      reject(error);
    });
    client.on("message", (message) => {
      clearTimeout(timeout);
      client.close();
      resolve(JSON.parse(message.toString("utf8")));
    });
    client.bind(() => {
      client.setBroadcast(true);
      client.send(
        Buffer.from(DISCOVERY_MESSAGE),
        discoveryPort,
        "255.255.255.255"
      );
    });
  });

  assert.equal(discoveryReply.service, "pocketlens");
  assert.equal(discoveryReply.port, port);
  assert.equal(discoveryReply.name, receiver.name);

  const health = await request(port, "/health");
  assert.equal(health.status, 200);
  const noLiveFrameStream = await request(port, "/frames.raw");
  assert.equal(noLiveFrameStream.status, 503);

  const noCameraFrame = await request(port, "/frame.jpg");
  assert.equal(noCameraFrame.status, 503);

  const frame = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
  const packet = Buffer.alloc(4 + frame.length);
  packet.writeUInt32BE(frame.length);
  frame.copy(packet, 4);
  const upload = await openUpload(port, packet);
  let state;
  for (let attempt = 0; attempt < 30; attempt += 1) {
    state = await request(port, "/status");
    if (JSON.parse(state.body).hasFrame) break;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  assert.equal(state.status, 200);
  assert.equal(JSON.parse(state.body).connected, true);
  assert.equal(JSON.parse(state.body).hasFrame, true);

  const cameraFrame = await request(port, "/frame.jpg", { raw: true });
  assert.equal(cameraFrame.status, 200);
  assert.deepEqual(cameraFrame.body, frame);

  const frameStream = await openFrameStream(port);
  assert.equal(frameStream.status, 200);
  assert.deepEqual(await frameStream.nextFrame(), frame);
  const nextFrame = Buffer.from([0xff, 0xd8, 0x7f, 0xff, 0xd9]);
  const nextPacket = Buffer.alloc(4 + nextFrame.length);
  nextPacket.writeUInt32BE(nextFrame.length);
  nextFrame.copy(nextPacket, 4);
  upload.write(nextPacket);
  assert.deepEqual(await frameStream.nextFrame(), nextFrame);
  frameStream.close();

  const busy = await request(port, "/ingest", { method: "POST" });
  assert.equal(busy.status, 409);

  const preview = await request(port, "/stream.mjpg", { firstChunk: true });
  assert.equal(preview.status, 200);
  assert.match(preview.chunk.toString("utf8"), /Content-Type: image\/jpeg/);
  preview.req.destroy();
  upload.destroy();
});
