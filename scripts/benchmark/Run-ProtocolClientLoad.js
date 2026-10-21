'use strict';

// Offline 26.3 protocol-client load probe for a disposable MultiPaper proxy.
// Requires minecraft-protocol 26.3 support; set NODE_PATH if installed elsewhere.
const minecraftProtocol = require('minecraft-protocol');

const [host = '127.0.0.1', portText = '25565', countText = '8', staggerText = '1000', durationText = '120000', prefix = 'Bench', movementMode = 'moving'] = process.argv.slice(2);
const port = Number(portText);
const count = Number(countText);
const staggerMs = Number(staggerText);
const durationMs = Number(durationText);

if (![port, count, staggerMs, durationMs].every(Number.isFinite) || count < 1 || count > 100 || staggerMs < 0 || durationMs < 1000 || !['moving', 'stationary'].includes(movementMode)) {
  throw new Error('Usage: node Run-ProtocolClientLoad.js <host> <port> <count 1-100> <stagger-ms> <duration-ms> [name-prefix] [moving|stationary]');
}

let tcpConnected = 0;
let ready = 0;
let chunkPackets = 0;
let movementPackets = 0;
let disconnects = 0;
const errors = [];
const clients = [];
const startedAt = Date.now();

function connect(index) {
  const username = `${prefix}${String(index + 1).padStart(2, '0')}`.slice(0, 16);
  const client = minecraftProtocol.createClient({ host, port, username, auth: 'offline', version: '26.3' });
  let loaded = false;
  let moving = false;
  let currentPosition = null;
  let movementTimer = null;
  clients.push(client);

  client.on('connect', () => {
    tcpConnected++;
    console.log(JSON.stringify({ event: 'tcp_connected', username, tcpConnected, elapsedMs: Date.now() - startedAt }));
  });

  client.on('packet', (data, metadata) => {
    if (metadata.name === 'map_chunk' || metadata.name === 'level_chunk_with_light') chunkPackets++;
    if (metadata.name === 'position') {
      currentPosition = { x: Number(data.x), y: Number(data.y), z: Number(data.z) };
      client.write('teleport_confirm', {
        teleportId: data.teleportId,
        x: data.x,
        y: data.y,
        z: data.z,
        yRot: data.yaw,
        xRot: data.pitch,
      });
      if (!loaded) {
        loaded = true;
        ready++;
        client.write('player_loaded', {});
        client.write('player_input', { inputs: {} });
        client.write('tick_end', {});
        console.log(JSON.stringify({ event: 'ready', username, ready, chunks: chunkPackets, elapsedMs: Date.now() - startedAt }));
      }
    }
    if (movementMode === 'moving' && metadata.name === 'map_chunk' && loaded && !moving && chunkPackets >= 150) {
      moving = true;
      const angle = (Math.PI * 2 * index) / count;
      const dx = Math.cos(angle) * 0.06;
      const dz = Math.sin(angle) * 0.06;
      movementTimer = setInterval(() => {
        if (!currentPosition || client.ended) return;
        currentPosition.x += dx;
        currentPosition.z += dz;
        client.write('player_input', { inputs: {} });
        client.write('position', {
          x: currentPosition.x,
          y: currentPosition.y,
          z: currentPosition.z,
          flags: { onGround: true, hasHorizontalCollision: false },
        });
        client.write('tick_end', {});
        movementPackets++;
      }, 50);
      console.log(JSON.stringify({ event: 'movement_started', username, elapsedMs: Date.now() - startedAt }));
    }
    if (metadata.name === 'disconnect') {
      disconnects++;
      console.log(JSON.stringify({ event: 'server_disconnect', username, data, disconnects, elapsedMs: Date.now() - startedAt }));
    }
  });

  client.on('error', error => {
    errors.push(`${username}: ${error.message}`);
    console.error(JSON.stringify({ event: 'client_error', username, message: error.message }));
  });
  client.on('end', () => {
    if (movementTimer) clearInterval(movementTimer);
    console.log(JSON.stringify({ event: 'ended', username, elapsedMs: Date.now() - startedAt }));
  });
}

for (let index = 0; index < count; index++) {
  setTimeout(() => connect(index), index * staggerMs);
}

setTimeout(() => {
  const summary = { event: 'summary', requested: count, tcpConnected, ready, chunkPackets, movementPackets, movementMode, disconnects, errors, durationMs: Date.now() - startedAt };
  console.log(JSON.stringify(summary));
  for (const client of clients) client.end('protocol load probe complete');
  setTimeout(() => process.exit(errors.length === 0 && ready === count ? 0 : 1), 2000);
}, durationMs);
