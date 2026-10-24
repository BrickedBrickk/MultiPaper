'use strict'

// Disposable offline-client load probe for Minecraft 26.3. This records TCP
// close/end events separately from protocol disconnect packets so the client
// report can be reconciled with server-side /list samples.

const [host = '127.0.0.1', portText = '25565', countText = '8', durationText = '120', prefix = 'MoveProbe', startupDelayText = '15'] = process.argv.slice(2)
const port = Number(portText)
const count = Number(countText)
const durationSeconds = Number(durationText)
const startupDelaySeconds = Number(startupDelayText)
const onGround = process.env.MULTIPAPER_BOT_ON_GROUND !== 'false'
const stationary = process.env.MULTIPAPER_BOT_STATIONARY === '1'
const traceClientWrites = process.env.MULTIPAPER_TRACE_CLIENT_WRITES === '1'
const traceClientPackets = process.env.MULTIPAPER_TRACE_CLIENT_PACKETS === '1'
const skipInitialPosition = process.env.MULTIPAPER_BOT_SKIP_INITIAL_POSITION === '1'
const movementStep = Number(process.env.MULTIPAPER_BOT_MOVEMENT_STEP || '0.21')
const initialPositionDelayMs = Number(process.env.MULTIPAPER_BOT_INITIAL_POSITION_DELAY_MS || '100')
const connectIntervalMs = Number(process.env.MULTIPAPER_BOT_CONNECT_INTERVAL_MS || '1000')
const loginTimeoutMs = Number(process.env.MULTIPAPER_BOT_LOGIN_TIMEOUT_MS || '15000')
const initialChunkQuietMs = Number(process.env.MULTIPAPER_BOT_CHUNK_QUIET_MS || '250')
const movementMinX = Number(process.env.MULTIPAPER_BOT_MIN_X || '96')
const movementMaxX = Number(process.env.MULTIPAPER_BOT_MAX_X || '224')
const version = '26.3'
const dataDir = process.env.MULTIPAPER_MC_DATA_26_3

if (!dataDir || !Number.isInteger(port) || port < 1 || port > 65535 || !Number.isInteger(count) || count < 1 || count > 20 || !Number.isFinite(durationSeconds) || durationSeconds < 10 || durationSeconds > 1800 || !Number.isFinite(startupDelaySeconds) || startupDelaySeconds < 0 || startupDelaySeconds > 60 || !Number.isFinite(movementStep) || movementStep <= 0 || !Number.isFinite(initialPositionDelayMs) || initialPositionDelayMs < 0 || initialPositionDelayMs > 5000 || !Number.isFinite(connectIntervalMs) || connectIntervalMs < 100 || connectIntervalMs > 30000 || !Number.isFinite(loginTimeoutMs) || loginTimeoutMs < 5000 || loginTimeoutMs > 90000 || !Number.isFinite(initialChunkQuietMs) || initialChunkQuietMs < 50 || initialChunkQuietMs > 5000 || !Number.isFinite(movementMinX) || !Number.isFinite(movementMaxX) || movementMinX >= movementMaxX || !/^[A-Za-z0-9_]{1,10}$/.test(prefix)) {
  console.error('Usage: MULTIPAPER_MC_DATA_26_3=<minecraft-data/26.3> node scripts/Run-ProtocolMovingClients.js host port [count [duration-seconds [username-prefix [startup-delay-seconds]]]]')
  process.exit(2)
}

const { mapped } = require('./Minecraft26_3ProtocolSupport')
const mc = require('minecraft-protocol')
const { isDeepStrictEqual } = require('node:util')

function encodeVarInt(value) {
  const bytes = []
  do {
    let byte = value & 0x7f
    value >>>= 7
    if (value !== 0) byte |= 0x80
    bytes.push(byte)
  } while (value !== 0)
  return bytes
}

function encodeEntityMovePacket(packetId, steps) {
  const packet = [...encodeVarInt(packetId), ...encodeVarInt(7)]
  const properties = (steps.length === 1 && steps[0].ticks === 0 ? 0 : steps.length) << 1 | 1
  packet.push(...encodeVarInt(properties))
  for (const step of steps) {
    if (properties >>> 1 !== 0) packet.push(...encodeVarInt(step.ticks))
    const delta = Buffer.alloc(6)
    delta.writeInt16BE(step.dX, 0)
    delta.writeInt16BE(step.dY, 2)
    delta.writeInt16BE(step.dZ, 4)
    packet.push(...delta)
  }
  return Buffer.from(packet)
}

function validateEntityDeltaCodec() {
  const packetMappings = mapped.protocol.play.toClient.types.packet[1][0].type[1].mappings
  const packetEntry = Object.entries(packetMappings).find(([, name]) => name === 'rel_entity_move')
  if (!packetEntry) throw new Error('26.3 clientbound rel_entity_move packet is missing from protocol data')

  const packetId = Number.parseInt(packetEntry[0], 16)
  const deserializer = mc.createDeserializer({ state: 'play', isServer: false, version })
  const packets = [
    { steps: [{ dX: 12, dY: -4, dZ: 9, ticks: 0 }] },
    { steps: [{ dX: 2, dY: 1, dZ: -3, ticks: 1 }, { dX: 6, dY: -2, dZ: 4, ticks: 137 }] }
  ]

  for (const expected of packets) {
    const decoded = deserializer.parsePacketBuffer(encodeEntityMovePacket(packetId, expected.steps)).data
    if (decoded.name !== 'rel_entity_move' || decoded.params.entityId !== 7 ||
        !decoded.params.move.onGround || !isDeepStrictEqual(decoded.params.move.steps, expected.steps)) {
      throw new Error(`26.3 entity-delta codec mismatch: ${JSON.stringify(decoded)}`)
    }
  }
}

validateEntityDeltaCodec()
if (process.env.MULTIPAPER_PROTOCOL_PREFLIGHT_ONLY === '1') {
  process.stdout.write('PASS: 26.3 linear and stepped entity-move packets decode correctly.\n')
  process.exit(0)
}

const elapsed = () => Math.round(performance.now())
const startedAt = performance.now()
const records = []
const clients = []
let movementStartedAt = null
let ending = false
let startTimer = null
let finishTimer = null

function event(record, name, fields = {}) {
  const item = { event: name, username: record.username, elapsedMs: elapsed(), ...fields }
  record.events.push(item)
  process.stdout.write(`${JSON.stringify(item)}\n`)
}

function startMovement() {
  if (movementStartedAt !== null || ending || records.some(record => !record.ready)) return
  movementStartedAt = performance.now()
  process.stdout.write(`${JSON.stringify({ event: stationary ? 'stationary_load_started' : 'movement_started', elapsedMs: elapsed(), ready: records.filter(record => record.ready).length, onGround })}\n`)
  if (stationary) {
    finishTimer = setTimeout(finish, durationSeconds * 1000)
    return
  }
  for (const record of records) {
    record.movementTimer = setInterval(() => {
      if (ending || !record.ready || !record.position || record.endedAt !== null) return
      // The defaults walk at 4.2 blocks/second on the probe's platform from
      // x=96 through x=224. Override the step and bounds for other fixtures.
      record.position.x += record.direction * movementStep
      if (record.position.x >= movementMaxX) record.direction = -1
      if (record.position.x <= movementMinX) record.direction = 1
      record.client.write('position', {
        x: record.position.x,
        y: record.position.y,
        z: record.position.z,
        flags: { onGround, hasHorizontalCollision: false }
      })
      record.client.write('tick_end', {})
      record.movementPackets++
    }, 50)
  }
  finishTimer = setTimeout(finish, durationSeconds * 1000)
}

function finish() {
  if (ending) return
  ending = true
  clearTimeout(startTimer)
  clearTimeout(finishTimer)
  for (const record of records) {
    clearInterval(record.movementTimer)
    if (record.endedAt === null) {
      record.expectedEnd = true
      record.client.end('moving-client probe complete')
    }
  }
  setTimeout(() => {
    const summary = {
      event: 'summary',
      requested: count,
      ready: records.filter(record => record.ready).length,
      protocolDisconnects: records.filter(record => record.disconnectPacket).length,
      unexpectedSocketEnds: records.filter(record => record.endedAt !== null && !record.expectedEnd).length,
      errors: records.flatMap(record => record.errors.map(error => ({ username: record.username, error }))),
      movementPackets: records.reduce((total, record) => total + record.movementPackets, 0),
      durationSeconds: movementStartedAt === null ? 0 : Math.round((performance.now() - movementStartedAt) / 1000),
      clients: records.map(record => ({
        username: record.username,
        connected: record.connected,
        ready: record.ready,
        expectedEnd: record.expectedEnd,
        endedAtMs: record.endedAt,
        disconnectPacket: record.disconnectPacket,
        errorCount: record.errors.length,
        chunks: record.chunks,
        packetsBeforeReady: [...record.receivedPacketNames].sort(),
        movementPackets: record.movementPackets,
        finalPositionSent: record.position,
        lastServerPosition: record.serverPosition
      }))
    }
    process.stdout.write(`${JSON.stringify(summary)}\n`)
  const failed = summary.ready !== count || summary.protocolDisconnects !== 0 || summary.unexpectedSocketEnds !== 0 || summary.errors.length !== 0 || summary.durationSeconds < durationSeconds - 1
    process.exit(failed ? 1 : 0)
  }, 1000)
}

function finishLogin(record) {
  if (record.ready || !record.initialPositionReceived || !record.initialChunkBatchFinished) return
  record.ready = true
  record.client.write('player_loaded', {})
  record.client.write('player_input', { inputs: {} })
  if (skipInitialPosition) {
    event(record, 'initial_position_sent', { position: record.position, skipped: true })
  } else {
    // Sending movement in the same packet batch as player_loaded lets the server
    // validate it before the initial spawn has completed on the backend.
    setTimeout(() => {
      if (ending || record.endedAt !== null) return
      record.client.write('position', {
        x: record.position.x,
        y: record.position.y,
        z: record.position.z,
        flags: { onGround, hasHorizontalCollision: false }
      })
      event(record, 'initial_position_sent', { position: record.position })
    }, initialPositionDelayMs)
  }
  event(record, 'ready', { position: record.position, chunks: record.chunks })
  if (records.length === count && records.every(client => client.ready)) {
    startTimer = setTimeout(startMovement, startupDelaySeconds * 1000)
  }
}

function scheduleFinishLoginAfterChunks(record) {
  if (!record.initialPositionReceived || record.ready || record.chunks === 0) return
  clearTimeout(record.initialChunkTimer)
  record.initialChunkTimer = setTimeout(() => {
    record.initialChunkBatchFinished = true
    finishLogin(record)
  }, initialChunkQuietMs)
}

function connect(index) {
  const username = `${prefix}${String(index + 1).padStart(2, '0')}`
  const record = {
    username,
    client: null,
    events: [],
    connected: false,
    ready: false,
    disconnectPacket: null,
    errors: [],
    chunks: 0,
    receivedPacketNames: new Set(),
    initialPositionReceived: false,
    initialChunkBatchFinished: false,
    movementPackets: 0,
    position: null,
    serverPosition: null,
    endedAt: null,
    expectedEnd: false,
    direction: index % 2 === 0 ? 1 : -1,
    movementTimer: null
  }
  records.push(record)
  record.client = mc.createClient({ host, port, username, auth: 'offline', version })
  if (traceClientWrites) {
    const write = record.client.write.bind(record.client)
    record.client.write = (name, params) => {
      if (['position', 'position_look', 'look', 'tick_end', 'player_loaded', 'player_input', 'teleport_confirm'].includes(name)) {
        event(record, 'client_packet_write', { packet: name, params })
      }
      return write(name, params)
    }
  }
  record.client.on('connect', () => {
    record.connected = true
    event(record, 'tcp_connected')
  })
  record.client.on('packet', (packet, meta) => {
    if (traceClientPackets && !record.ready) record.receivedPacketNames.add(meta.name)
    if (meta.name === 'position') {
      const previous = record.serverPosition
      record.position = { x: packet.x, y: packet.y, z: packet.z }
      record.serverPosition = { ...record.position }
      if (record.ready && previous && (Math.abs(packet.x - previous.x) > 0.5 || Math.abs(packet.y - previous.y) > 0.5 || Math.abs(packet.z - previous.z) > 0.5)) {
        event(record, 'server_position_update', { position: record.serverPosition })
      }
      record.client.write('teleport_confirm', {
        teleportId: packet.teleportId,
        x: packet.x,
        y: packet.y,
        z: packet.z,
        yRot: packet.yaw,
        xRot: packet.pitch
      })
      if (!record.ready) {
        record.initialPositionReceived = true
        finishLogin(record)
      }
      return
    }
    if (meta.name === 'map_chunk') {
      record.chunks++
      scheduleFinishLoginAfterChunks(record)
    }
    if (meta.name === 'chunk_batch_finished') {
      record.initialChunkBatchFinished = true
      finishLogin(record)
    }
    if (meta.name === 'disconnect') {
      record.disconnectPacket = packet
      event(record, 'protocol_disconnect', { packet })
    }
  })
  record.client.on('end', reason => {
    record.endedAt = elapsed()
    event(record, record.expectedEnd ? 'expected_socket_end' : 'unexpected_socket_end', { reason: String(reason || '') })
  })
  record.client.on('error', error => {
    record.errors.push(error.message)
    event(record, 'client_error', { error: error.message })
  })
  event(record, 'client_started')
}

for (let i = 0; i < count; i++) setTimeout(() => connect(i), i * connectIntervalMs)
setTimeout(() => {
  if (movementStartedAt === null) {
    process.stdout.write(`${JSON.stringify({ event: 'failed_to_start_movement', elapsedMs: elapsed(), ready: records.filter(record => record.ready).length, requested: count })}\n`)
    finish()
  }
}, loginTimeoutMs + startupDelaySeconds * 1000 + durationSeconds * 1000 + 30000)
