'use strict'

// Disposable 26.3 protocol probe: craft a crafting table from four spruce
// planks in the player's 2x2 inventory grid using normal window clicks.
// Seed exactly four planks into hotbar slot 0 with RCON while the player is online.

require('./Minecraft26_3ProtocolSupport')
const mc = require('minecraft-protocol')

const [host = '127.0.0.1', portText = '25565', username, version = '26.3', holdText = '12'] = process.argv.slice(2)
const port = Number(portText)
const holdSeconds = Number(holdText)
if (!username || username.length > 16 || !Number.isInteger(port) || port < 1 || port > 65535 || !Number.isFinite(holdSeconds) || holdSeconds < 0 || holdSeconds > 20) {
  console.error('Usage: node scripts/Run-PlayerCraftingProbe.js host port username [version [hold-seconds]]')
  process.exit(2)
}

const protocolPath = require.resolve('minecraft-protocol')
const data = require(require.resolve('minecraft-data', { paths: [protocolPath] }))(version)
const planksId = data.itemsByName.spruce_planks.id
const tableId = data.itemsByName.crafting_table.id
const client = mc.createClient({ host, port, username, auth: 'offline', version })
let ready = false
let position = null
let stateId = 0
let stage = 0
let ended = false
let outputReady = false
let outputClickSent = false

function finish(code, message) {
  if (ended) return
  ended = true
  clearInterval(movementInterval)
  clearTimeout(timeout)
  console.log(message)
  client.end(code === 0 ? 'crafting probe complete' : 'crafting probe failed')
  setTimeout(() => process.exit(code), 250)
}

function click(slot, button, label) {
  console.log(`CLICK ${label}: slot=${slot}, button=${button}, stateId=${stateId}`)
  client.write('window_click', { windowId: 0, stateId, slot, mouseButton: button, mode: 0, changedSlots: [], cursorItem: null })
}

function holdSuccess() {
  console.log('PASS: crafted one crafting table from four spruce planks and placed it into player inventory slot 9.')
  setTimeout(() => finish(0, 'PASS: player crafting transaction completed.'), holdSeconds * 1000)
}

function takeOutputWhenReady() {
  if (stage === 6 && outputReady && !outputClickSent) {
    outputClickSent = true
    stage = 7
    setTimeout(() => click(0, 0, 'take-crafted-table'), 180)
  }
}

client.on('packet', (packet, meta) => {
  if (meta.name === 'position') {
    position = { x: packet.x, y: packet.y, z: packet.z }
    client.write('teleport_confirm', { teleportId: packet.teleportId, x: packet.x, y: packet.y, z: packet.z, yRot: packet.yaw, xRot: packet.pitch })
    if (!ready) {
      ready = true
      client.write('player_loaded', {})
      client.write('player_input', { inputs: {} })
      console.log(`PLAYER_READY ${JSON.stringify(position)}`)
    }
    return
  }

  if (meta.name === 'window_items' && packet.windowId === 0) {
    stateId = packet.stateId
    const hotbar = packet.items[36]
    if (stage === 0 && hotbar?.itemId === planksId && hotbar.itemCount === 4) {
      stage = 1
      setTimeout(() => click(36, 0, 'pick-up-four-planks'), 150)
    }
    return
  }

  if (meta.name === 'set_slot') {
    if (packet.windowId === 0) stateId = packet.stateId
    const item = packet.item
    if (packet.windowId === 0 && packet.slot === 0 && item.itemId === tableId && item.itemCount === 1) {
      outputReady = true
      takeOutputWhenReady()
    }
    if (stage === 0 && packet.windowId === 0 && packet.slot === 36 && item.itemId === planksId && item.itemCount === 4) {
      stage = 1
      setTimeout(() => click(36, 0, 'pick-up-four-planks'), 150)
    } else if (stage === 1 && packet.windowId === 0 && packet.slot === 36 && item.itemCount === 0) {
      stage = 2
      setTimeout(() => click(1, 1, 'place-plank-grid-1'), 180)
    } else if (stage >= 2 && stage <= 5 && packet.windowId === 0 && packet.slot === stage - 1 && item.itemId === planksId && item.itemCount === 1) {
      const nextSlot = stage
      stage++
      if (nextSlot <= 4) setTimeout(() => click(nextSlot, 1, `place-plank-grid-${nextSlot}`), 180)
    } else if (stage === 8 && packet.windowId === 0 && packet.slot === 9 && item.itemId === tableId && item.itemCount === 1) {
      stage = 9
      setTimeout(holdSuccess, 250)
    }
    takeOutputWhenReady()
    return
  }

  if (meta.name === 'set_cursor_item' && stage === 7 && packet.contents.itemId === tableId && packet.contents.itemCount === 1) {
    stage = 8
    setTimeout(() => click(9, 0, 'place-table-in-inventory-slot-9'), 180)
    return
  }

  if (meta.name === 'disconnect') finish(1, `FAIL: disconnected: ${JSON.stringify(packet)}`)
})

client.on('error', error => finish(1, `FAIL: ${error.message}`))
const movementInterval = setInterval(() => {
  if (!ready || !position || ended) return
  client.write('position', { x: position.x, y: position.y, z: position.z, flags: { onGround: true, hasHorizontalCollision: false } })
  client.write('tick_end', {})
}, 500)
const timeout = setTimeout(() => finish(1, `FAIL: timed out at crafting stage ${stage}.`), 35000)
