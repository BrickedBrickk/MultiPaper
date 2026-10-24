'use strict'

// Disposable protocol probe for a real player-owned ender pearl.
// The caller should prepare the stasis chamber and give the player an ender
// pearl before throwDelayMs expires. This script does not grant items or teleport.
const mc = require('minecraft-protocol')
require('./Minecraft26_3ProtocolSupport')

const [host = '127.0.0.1', portText = '25565', username = 'PearlPersist01', throwDelayText = '12000', holdDurationText = '180000', version = '26.3', yawText = '0', pitchText = '0'] = process.argv.slice(2)
const port = Number(portText)
const throwDelayMs = Number(throwDelayText)
const holdDurationMs = Number(holdDurationText)
const throwYaw = Number(yawText)
const throwPitch = Number(pitchText)
if (!Number.isInteger(port) || port < 1 || port > 65535 || !Number.isInteger(throwDelayMs) || throwDelayMs < 1000 || !Number.isInteger(holdDurationMs) || holdDurationMs < 10000 || username.length > 16 || !Number.isFinite(throwYaw) || !Number.isFinite(throwPitch) || throwPitch < -90 || throwPitch > 90) {
  console.error('Usage: node scripts/Run-PlayerPearlPersistenceProbe.js [host [port [username [throwDelayMs [holdDurationMs [version [yaw [pitch]]]]]]]]')
  process.exit(2)
}

const protocolPath = require.resolve('minecraft-protocol')
const minecraftData = require(require.resolve('minecraft-data', { paths: [protocolPath] }))(version)
const pearlType = minecraftData.entitiesByName.ender_pearl.id
const client = mc.createClient({ host, port, username, auth: 'offline', version })

let ready = false
let ended = false
let position = null
let yaw = 0
let pitch = 0
let throwSent = false
let pearlSpawn = null
let pearlVelocityUpdates = 0
let pearlLastVelocity = null
let timer

function finish(reason) {
  if (ended) return
  ended = true
  clearInterval(movementTimer)
  clearTimeout(timer)
  clearTimeout(watchdog)
  console.log(`SUMMARY ${JSON.stringify({ ready, throwSent, pearlSpawn, pearlVelocityUpdates, pearlLastVelocity, reason })}`)
  client.end('player-owned pearl probe complete')
}

client.on('connect', () => console.log(`TCP_CONNECTED ${host}:${port}`))
client.on('packet', (packet, meta) => {
  if (meta.name === 'position') {
    const previousPosition = position
    position = { x: Number(packet.x), y: Number(packet.y), z: Number(packet.z) }
    yaw = Number(packet.yaw)
    pitch = Number(packet.pitch)
    client.write('teleport_confirm', {
      teleportId: packet.teleportId,
      x: packet.x,
      y: packet.y,
      z: packet.z,
      yRot: packet.yaw,
      xRot: packet.pitch
    })
    if (previousPosition && (previousPosition.x !== position.x || previousPosition.y !== position.y || previousPosition.z !== position.z)) {
      console.log(`SERVER_POSITION ${JSON.stringify({ ...position, yaw, pitch })}`)
    }
    if (!ready) {
      ready = true
      client.write('player_loaded', {})
      client.write('player_input', { inputs: {} })
      yaw = throwYaw
      pitch = throwPitch
      client.write('look', { yaw, pitch, flags: { onGround: false, hasHorizontalCollision: false } })
      client.write('tick_end', {})
      console.log(`PLAYER_READY ${JSON.stringify({ ...position, yaw, pitch })}`)
      console.log(`CLIENT_LOOK ${JSON.stringify({ yaw, pitch })}`)
      timer = setTimeout(() => {
        if (ended) return
        throwSent = true
        client.write('use_item', { hand: 0, sequence: 1, rotation: { x: throwYaw, y: throwPitch } })
        console.log(`USE_ITEM ${JSON.stringify({ yaw: throwYaw, pitch: throwPitch })}`)
      }, throwDelayMs)
    }
    return
  }

  if (meta.name === 'spawn_entity' && Number(packet.type) === pearlType) {
    pearlSpawn = {
      uuid: packet.objectUUID,
      entityId: packet.entityId,
      position: { x: Number(packet.x), y: Number(packet.y), z: Number(packet.z) },
      velocity: packet.velocity,
      yaw: packet.yaw,
      pitch: packet.pitch
    }
    console.log(`ENDER_PEARL_SPAWN ${JSON.stringify(pearlSpawn)}`)
  } else if (meta.name === 'entity_teleport' && pearlSpawn && packet.entityId === pearlSpawn.entityId) {
    console.log(`ENDER_PEARL_TELEPORT ${JSON.stringify(packet)}`)
  } else if (meta.name === 'entity_velocity' && pearlSpawn && packet.entityId === pearlSpawn.entityId) {
    pearlVelocityUpdates++
    pearlLastVelocity = packet.velocity
    if (pearlVelocityUpdates === 1) console.log(`ENDER_PEARL_VELOCITY_FIRST ${JSON.stringify(packet)}`)
  } else if (meta.name === 'remove_entities' && pearlSpawn && packet.entityIds?.includes(pearlSpawn.entityId)) {
    console.log(`ENDER_PEARL_REMOVED ${JSON.stringify(packet)}`)
  } else if (meta.name === 'respawn') {
    console.log(`RESPAWN ${JSON.stringify(packet)}`)
  } else if (meta.name === 'disconnect') {
    console.log(`DISCONNECT ${JSON.stringify(packet)}`)
    finish('server disconnected client')
  }
})

client.on('error', error => finish(`client error: ${error.message}`))
client.on('end', reason => {
  if (!ended) finish(`connection ended: ${reason || 'socket closed'}`)
})

const movementTimer = setInterval(() => {
  if (!ready || !position || ended) return
  client.write('player_input', { inputs: {} })
  client.write('tick_end', {})
}, 100)

const watchdog = setTimeout(() => finish('probe timeout'), throwDelayMs + holdDurationMs)
