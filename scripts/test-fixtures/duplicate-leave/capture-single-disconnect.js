'use strict'

require('../../Minecraft26_3ProtocolSupport')
const mc = require('minecraft-protocol')
const fs = require('node:fs')

const [host, ownerPortText, peerPortText, outputPath] = process.argv.slice(2)
const ownerPort = Number(ownerPortText)
const peerPort = Number(peerPortText)
if (!host || !Number.isInteger(ownerPort) || !Number.isInteger(peerPort) || ownerPort < 1 || peerPort < 1 || !outputPath) {
  console.error('Usage: node capture-single-disconnect.js host ownerBackendPort peerBackendPort output.json')
  process.exit(2)
}

const clients = []
const observations = { ownerObserver: [], peerObserver: [] }
let captureEnabled = false
let stopped = false
const startedAt = Date.now()

function flatten(value) {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map(flatten).join(' ')
  if (!value || typeof value !== 'object') return ''
  return Object.values(value).map(flatten).filter(Boolean).join(' ')
}

function connect(username, port, observerName) {
  return new Promise((resolve, reject) => {
    const client = mc.createClient({ host, port, username, auth: 'offline', version: '26.3' })
    clients.push(client)
    let ready = false
    const timer = setTimeout(() => reject(new Error(`${username} did not receive initial position within 25 seconds`)), 25000)
    client.on('packet', (packet, meta) => {
      if (meta.name === 'position' && !ready) {
        client.write('teleport_confirm', {
          teleportId: packet.teleportId,
          x: packet.x,
          y: packet.y,
          z: packet.z,
          yRot: packet.yaw,
          xRot: packet.pitch
        })
        client.write('player_loaded', {})
        client.write('player_input', { inputs: {} })
        ready = true
        clearTimeout(timer)
        resolve(client)
      } else if (observerName && meta.name === 'system_chat' && captureEnabled) {
        const text = flatten(packet)
        if (/multiplayer\.player\.left|LeaveOwner.{0,80}left the game/i.test(text)) {
          observations[observerName].push({ elapsedMs: Date.now() - startedAt, packet })
        }
      }
    })
    client.on('error', error => {
      clearTimeout(timer)
      if (!ready) reject(error)
    })
    client.on('end', () => clearTimeout(timer))
  })
}

async function closeClients() {
  await Promise.all(clients.map(client => new Promise(resolve => {
    if (client.ended) return resolve()
    const timer = setTimeout(resolve, 2000)
    client.once('end', () => { clearTimeout(timer); resolve() })
    client.end('disposable leave-message capture complete')
  })))
}

async function main() {
  const owner = await connect('LeaveOwner', ownerPort)
  const ownerObserver = await connect('LeaveSeeA', ownerPort, 'ownerObserver')
  const peerObserver = await connect('LeaveSeeB', peerPort, 'peerObserver')
  await new Promise(resolve => setTimeout(resolve, 2000))
  captureEnabled = true
  await new Promise(resolve => setTimeout(resolve, 300))
  owner.end('single synthetic disconnect')
  await new Promise(resolve => setTimeout(resolve, 6000))
  const firstDisconnect = {
    ownerObserverMessages: observations.ownerObserver.length,
    peerObserverMessages: observations.peerObserver.length
  }
  await new Promise(resolve => setTimeout(resolve, 1000))
  const reconnectedOwner = await connect('LeaveOwner', peerPort)
  await new Promise(resolve => setTimeout(resolve, 1000))
  reconnectedOwner.end('synthetic disconnect after reconnect on peer backend')
  await new Promise(resolve => setTimeout(resolve, 6000))
  const secondDisconnect = {
    ownerObserverMessages: observations.ownerObserver.length - firstDisconnect.ownerObserverMessages,
    peerObserverMessages: observations.peerObserver.length - firstDisconnect.peerObserverMessages
  }

  captureEnabled = false

  const result = {
    scenario: 'owner disconnect on backend A, reconnect on backend B, disconnect again; observers on both backends',
    owner: 'LeaveOwner',
    clientsReady: clients.length,
    firstDisconnect,
    secondDisconnect,
    ownerObserverMessages: observations.ownerObserver.length,
    peerObserverMessages: observations.peerObserver.length,
    totalObservedPerObserver: [observations.ownerObserver.length, observations.peerObserver.length],
    observations
  }
  fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' })
  console.log(`RESULT ${JSON.stringify(result)}`)

  const passed = firstDisconnect.ownerObserverMessages === 1 && firstDisconnect.peerObserverMessages === 1
    && secondDisconnect.ownerObserverMessages === 1 && secondDisconnect.peerObserverMessages === 1
  await closeClients()
  if (!passed) {
    console.error('FAIL: each backend observer must receive exactly one vanilla leave message for each disconnect, including reconnect on the peer backend.')
    process.exitCode = 1
  } else {
    console.log('PASS: exactly one vanilla leave message reached each observer.')
  }
}

const hardTimeout = setTimeout(async () => {
  if (stopped) return
  stopped = true
  console.error('FAIL: bounded disconnect capture exceeded 90 seconds.')
  await closeClients()
  process.exit(1)
}, 90000)

main().catch(async error => {
  console.error(`FAIL: ${error.stack || error.message}`)
  await closeClients()
  process.exitCode = 1
}).finally(() => {
  stopped = true
  clearTimeout(hardTimeout)
})
