'use strict'

// Disposable 26.3 protocol probe: open a chest through the ordinary block-use
// packet, then move its stack by normal clicks or shift-click and require
// server slot confirmations. Seed the chest with RCON before connecting.

const mc = require('minecraft-protocol')

const [host = '127.0.0.1', portText = '25565', username, xText = '5', yText = '65', zText = '5', version = '26.3', holdText = '12', clickMode = 'normal'] = process.argv.slice(2)
const port = Number(portText)
const block = { x: Number(xText), y: Number(yText), z: Number(zText) }
const holdSeconds = Number(holdText)
if (!username || username.length > 16 || !Number.isInteger(port) || port < 1 || port > 65535 || !Object.values(block).every(Number.isFinite) || !Number.isFinite(holdSeconds) || holdSeconds < 0 || holdSeconds > 20 || !['normal', 'shift'].includes(clickMode)) {
  console.error('Usage: node scripts/Run-PlayerContainerClickProbe.js host port username [x y z [version [hold-seconds [normal|shift]]]]')
  process.exit(2)
}

const protocolPath = require.resolve('minecraft-protocol')
const data = require(require.resolve('minecraft-data', { paths: [protocolPath] }))(version)
const diamondId = data.itemsByName.diamond.id
const client = mc.createClient({ host, port, username, auth: 'offline', version })
let ready = false
let position = null
let windowId = null
let stateId = 0
let stage = 0
let sequence = 0
let ended = false
let chestSlotCleared = false
let playerSlotFilled = false
let shiftDestinationSlot = null
let lastInteractionAt = 0

function finish(code, message) {
  if (ended) return
  ended = true
  clearInterval(movementInterval)
  clearTimeout(timeout)
  console.log(message)
  client.end(code === 0 ? 'container click probe complete' : 'container click probe failed')
  setTimeout(() => process.exit(code), 250)
}

function click(slot, label, mode = 0) {
  console.log(`CLICK ${label}: window=${windowId}, slot=${slot}, stateId=${stateId}`)
  client.write('window_click', { windowId, stateId, slot, mouseButton: 0, mode, changedSlots: [], cursorItem: null })
}

function confirmShiftTransfer() {
  if (clickMode === 'shift' && stage === 4 && chestSlotCleared && playerSlotFilled) {
    stage = 3
    setTimeout(() => {
      console.log(`PASS: shift-click moved exactly 16 diamonds from chest slot 0 into player inventory window slot ${shiftDestinationSlot} (hotbar slot ${shiftDestinationSlot - 54}).`)
      client.write('close_window', { windowId })
      setTimeout(() => finish(0, 'PASS: shift-click container transaction completed.'), holdSeconds * 1000)
    }, 250)
  }
}

function interactWithChest() {
  client.write('block_place', {
    hand: 0,
    location: block,
    direction: 1,
    cursorX: 0.5,
    cursorY: 0.5,
    cursorZ: 0.5,
    insideBlock: false,
    worldBorderHit: false,
    sequence: sequence++
  })
  lastInteractionAt = Date.now()
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

  if (meta.name === 'open_window') {
    windowId = packet.windowId
    console.log(`WINDOW_OPEN ${JSON.stringify(packet)}`)
    return
  }

  if (meta.name === 'window_items' && packet.windowId === windowId) {
    stateId = packet.stateId
    const chestSlot = packet.items[0]
    const playerSlot = packet.items[27]
    const destinationSlot = clickMode === 'shift'
      ? packet.items.findIndex((item, slot) => slot >= 27 && slot <= 62 && item?.itemId === diamondId && item.itemCount === 16)
      : -1
    if (clickMode === 'shift' && stage === 4) console.log(`SHIFT_WINDOW_ITEMS chest=${JSON.stringify(chestSlot)} destination=${destinationSlot} item=${JSON.stringify(destinationSlot >= 0 ? packet.items[destinationSlot] : null)} stateId=${stateId}`)
    if (stage === 0 && chestSlot?.itemId === diamondId && chestSlot.itemCount === 16) {
      stage = clickMode === 'shift' ? 4 : 1
      setTimeout(() => click(0, clickMode === 'shift' ? 'shift-click-chest-stack' : 'take-chest-stack', clickMode === 'shift' ? 1 : 0), 150)
    } else if (stage === 2 && chestSlot?.itemCount === 0 && playerSlot?.itemId === diamondId && playerSlot.itemCount === 16) {
      stage = 3
      setTimeout(() => {
        console.log('PASS: normal chest GUI clicks moved exactly 16 diamonds into player inventory slot 9.')
        client.write('close_window', { windowId })
        setTimeout(() => finish(0, 'PASS: container click transaction completed.'), holdSeconds * 1000)
      }, 250)
    } else if (stage === 4 && chestSlot?.itemCount === 0 && destinationSlot >= 0) {
      chestSlotCleared = true
      playerSlotFilled = true
      shiftDestinationSlot = destinationSlot
      confirmShiftTransfer()
    }
    return
  }

  if (meta.name === 'set_slot') {
    if (packet.windowId === windowId) stateId = packet.stateId
    if (clickMode === 'shift' && stage === 4) console.log(`SHIFT_SET_SLOT window=${packet.windowId} slot=${packet.slot} item=${JSON.stringify(packet.item)} stateId=${packet.stateId}`)
    if (clickMode === 'shift' && stage === 4 && packet.windowId === windowId) {
      if (packet.slot === 0 && packet.item.itemCount === 0) chestSlotCleared = true
      if (packet.slot >= 27 && packet.slot <= 62 && packet.item.itemId === diamondId && packet.item.itemCount === 16) {
        playerSlotFilled = true
        shiftDestinationSlot = packet.slot
      }
      confirmShiftTransfer()
    }
    if (stage === 1 && packet.windowId === windowId && packet.slot === 0 && packet.item.itemCount === 0) {
      chestSlotCleared = true
      stage = 2
      setTimeout(() => click(27, 'place-in-player-slot-9'), 200)
    } else if (stage === 2 && packet.windowId === windowId && packet.slot === 27 && packet.item.itemId === diamondId && packet.item.itemCount === 16 && chestSlotCleared) {
      stage = 3
      setTimeout(() => {
        console.log('PASS: normal chest GUI clicks moved exactly 16 diamonds into player inventory slot 9.')
        client.write('close_window', { windowId })
        setTimeout(() => finish(0, 'PASS: container click transaction completed.'), holdSeconds * 1000)
      }, 250)
    }
  }
  if (meta.name === 'disconnect') finish(1, `FAIL: disconnected: ${JSON.stringify(packet)}`)
})

client.on('error', error => finish(1, `FAIL: ${error.message}`))
const movementInterval = setInterval(() => {
  if (!ready || !position || ended) return
  client.write('position', { x: position.x, y: position.y, z: position.z, flags: { onGround: true, hasHorizontalCollision: false } })
  client.write('tick_end', {})
  if (windowId == null && Date.now() - lastInteractionAt >= 1200) interactWithChest()
}, 500)
const timeout = setTimeout(() => finish(1, `FAIL: timed out at container click stage ${stage}; window=${windowId}.`), 30000)
