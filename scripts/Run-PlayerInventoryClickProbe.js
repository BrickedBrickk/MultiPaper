'use strict'

// Disposable-fixture probe for normal survival inventory clicks.
// Seed exactly 8 diamonds into hotbar slot 0 while this player is online,
// then run: node scripts/Run-PlayerInventoryClickProbe.js host port username [version]
// The client moves that stack to inventory slot 9 and exits after server confirmation.

require('./Minecraft26_3ProtocolSupport')
const mc = require('minecraft-protocol')

const [host = '127.0.0.1', portText = '25565', username, version = '26.3'] = process.argv.slice(2)
const port = Number(portText)
if (!username || !Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('Usage: node scripts/Run-PlayerInventoryClickProbe.js host port username [version]')
  process.exit(2)
}

const protocolPath = require.resolve('minecraft-protocol')
const minecraftData = require(require.resolve('minecraft-data', { paths: [protocolPath] }))(version)
const diamondId = minecraftData.itemsByName.diamond.id
const client = mc.createClient({ host, port, username, auth: 'offline', version })

let ready = false
let stage = 0
let stateId = 0
let position = null
let ended = false

function finish(code, message) {
  if (ended) return
  ended = true
  clearInterval(movementInterval)
  console.log(message)
  client.end(code === 0 ? 'inventory click probe complete' : 'inventory click probe failed')
  setTimeout(() => process.exit(code), 250)
}

function click(slot, label) {
  console.log(`CLICK ${label}: slot=${slot}, stateId=${stateId}`)
  client.write('window_click', {
    windowId: 0,
    stateId,
    slot,
    mouseButton: 0,
    mode: 0,
    changedSlots: [],
    cursorItem: null
  })
}

client.on('connect', () => console.log(`CONNECTED ${host}:${port} as ${username}`))
client.on('packet', (data, meta) => {
  if (meta.name === 'position') {
    position = { x: data.x, y: data.y, z: data.z }
    client.write('teleport_confirm', {
      teleportId: data.teleportId,
      x: data.x,
      y: data.y,
      z: data.z,
      yRot: data.yaw,
      xRot: data.pitch
    })
    if (!ready) {
      ready = true
      client.write('player_loaded', {})
      client.write('player_input', { inputs: {} })
      console.log(`PLAYER_READY ${JSON.stringify(position)}`)
    }
    return
  }

  if (meta.name === 'window_items' && data.windowId === 0) {
    stateId = data.stateId
    const hotbarZero = data.items[36]
    if (stage === 0 && hotbarZero?.itemId === diamondId && hotbarZero.itemCount === 8) {
      stage = 1
      setTimeout(() => click(36, 'pickup-hotbar-0'), 100)
    }
    return
  }

  if (meta.name === 'set_slot' && data.windowId === 0) {
    stateId = data.stateId
    if (stage === 0 && data.slot === 36 && data.item.itemId === diamondId && data.item.itemCount === 8) {
      stage = 1
      setTimeout(() => click(36, 'pickup-hotbar-0'), 100)
    } else if (stage === 1 && data.slot === 36 && data.item.itemCount === 0) {
      stage = 2
      setTimeout(() => click(9, 'place-inventory-0'), 150)
    } else if (stage === 2 && data.slot === 9 && data.item.itemId === diamondId && data.item.itemCount === 8) {
      stage = 3
      setTimeout(() => finish(0, 'PASS: moved exactly 8 diamonds from hotbar slot 0 to inventory slot 0.'), 5000)
    }
    return
  }

  if (meta.name === 'disconnect') finish(1, `FAIL: disconnected: ${JSON.stringify(data)}`)
})

client.on('error', error => finish(1, `FAIL: ${error.message}`))
const movementInterval = setInterval(() => {
  if (!ready || !position || ended) return
  client.write('position', {
    x: position.x,
    y: position.y,
    z: position.z,
    flags: { onGround: true, hasHorizontalCollision: false }
  })
  client.write('tick_end', {})
}, 500)

setTimeout(() => {
  if (stage !== 3) finish(1, `FAIL: timeout at click stage ${stage}; expected 8 diamonds in hotbar slot 0.`)
}, 30000)


