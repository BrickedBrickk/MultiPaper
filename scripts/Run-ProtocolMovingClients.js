'use strict'

// Disposable offline-client load probe for Minecraft 26.3. This records TCP
// close/end events separately from protocol disconnect packets so the client
// report can be reconciled with server-side /list samples.

const fs = require('node:fs')
const path = require('node:path')

const [host = '127.0.0.1', portText = '25565', countText = '8', durationText = '120', prefix = 'MoveProbe', startupDelayText = '15'] = process.argv.slice(2)
const port = Number(portText)
const count = Number(countText)
const durationSeconds = Number(durationText)
const startupDelaySeconds = Number(startupDelayText)
const onGround = process.env.MULTIPAPER_BOT_ON_GROUND !== 'false'
const version = '26.3'
const dataDir = process.env.MULTIPAPER_MC_DATA_26_3

if (!dataDir || !Number.isInteger(port) || port < 1 || port > 65535 || !Number.isInteger(count) || count < 1 || count > 20 || !Number.isFinite(durationSeconds) || durationSeconds < 10 || durationSeconds > 1800 || !Number.isFinite(startupDelaySeconds) || startupDelaySeconds < 0 || startupDelaySeconds > 60 || !/^[A-Za-z0-9_]{1,10}$/.test(prefix)) {
  console.error('Usage: MULTIPAPER_MC_DATA_26_3=<minecraft-data/26.3> node scripts/Run-ProtocolMovingClients.js host port [count [duration-seconds [username-prefix [startup-delay-seconds]]]]')
  process.exit(2)
}

// The protocol client dependency available in the workspace has 26.3 packet
// definitions from PR 1301 in a separate data checkout. Expose those exact
// files to minecraft-data without copying or changing the cached dependency.
const mcDataEntry = require.resolve('minecraft-data')
const mcDataRoot = path.dirname(mcDataEntry)
const dataPaths = require(path.join(mcDataRoot, 'data.js'))
const protocolVersionsPath = path.join(mcDataRoot, 'minecraft-data', 'data', 'pc', 'common', 'protocolVersions.json')
const protocolVersions = require(protocolVersionsPath)
const release = protocolVersions.find(entry => entry.minecraftVersion === version)
const externalData = path.resolve(dataDir)
const base = dataPaths.pc['26.1']

if (!release || !fs.existsSync(path.join(externalData, 'protocol.json')) || !fs.existsSync(path.join(externalData, 'proto.yml'))) {
  throw new Error(`Incomplete Minecraft ${version} data: ${externalData}`)
}

const mapped = {}
for (const key of Object.keys(base)) {
  Object.defineProperty(mapped, key, { enumerable: true, configurable: true, get: () => base[key] })
}
for (const file of fs.readdirSync(externalData)) {
  const key = file.endsWith('.json') ? file.slice(0, -5) : file === 'proto.yml' ? 'proto' : null
  if (!key) continue
  Object.defineProperty(mapped, key, {
    enumerable: true,
    configurable: true,
    get: () => key === 'proto' ? path.join(externalData, file) : require(path.join(externalData, file))
  })
}
Object.defineProperty(mapped, 'version', {
  enumerable: true,
  configurable: true,
  get: () => ({ ...base.version, ...release, majorVersion: version })
})
dataPaths.pc[version] = mapped

const mc = require('minecraft-protocol')

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
        !decoded.params.move.onGround || JSON.stringify(decoded.params.move.steps) !== JSON.stringify(expected.steps)) {
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
  process.stdout.write(`${JSON.stringify({ event: 'movement_started', elapsedMs: elapsed(), ready: records.filter(record => record.ready).length, onGround })}\n`)
  for (const record of records) {
    record.movementTimer = setInterval(() => {
      if (ending || !record.ready || !record.position || record.endedAt !== null) return
      // A walking pace of 4.2 blocks/second, sent at 20 Hz. The disposable
      // fixture keeps a flat platform from x=80 through x=240; bounce through
      // this region to cross the two backend ownership areas repeatedly.
      record.position.x += record.direction * 0.21
      if (record.position.x >= 224) record.direction = -1
      if (record.position.x <= 96) record.direction = 1
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
  record.client.on('connect', () => {
    record.connected = true
    event(record, 'tcp_connected')
  })
  record.client.on('packet', (packet, meta) => {
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
        record.ready = true
        record.client.write('player_loaded', {})
        record.client.write('player_input', { inputs: {} })
        event(record, 'ready', { position: record.position })
        if (records.length === count && records.every(client => client.ready)) {
          startTimer = setTimeout(startMovement, startupDelaySeconds * 1000)
        }
      }
      return
    }
    if (meta.name === 'map_chunk') record.chunks++
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

for (let i = 0; i < count; i++) setTimeout(() => connect(i), i * 1000)
setTimeout(() => {
  if (movementStartedAt === null) {
    process.stdout.write(`${JSON.stringify({ event: 'failed_to_start_movement', elapsedMs: elapsed(), ready: records.filter(record => record.ready).length, requested: count })}\n`)
    finish()
  }
}, 45000 + startupDelaySeconds * 1000)
