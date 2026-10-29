'use strict'
require('../../Minecraft26_3ProtocolSupport.js')
const mc = require('minecraft-protocol')
const readline = require('node:readline')
const owners = new Map(), clients = new Map(), windows = new Map(), states = new Map(), snapshots = new Map()
const target = 'RC5GateTarget'
const inventoryKind = process.argv.at(-1) ?? 'main'
const clickScenario = process.argv.at(-2) ?? 'single'
if (!['main', 'ender'].includes(inventoryKind)) throw new Error('Inventory kind must be main or ender')
if (!['single', 'simultaneous'].includes(clickScenario)) throw new Error('Click scenario must be single or simultaneous')
const roles = [['a', 'RC5GateA', false], ['b', 'RC5GateB', true], ['t', target, true]]

function flatten(value) {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map(flatten).join(' ')
  if (value && typeof value === 'object') return Object.values(value).map(flatten).join(' ')
  return ''
}

function maybeReady() {
  if (roles.every(([key]) => owners.has(key))) console.log(`OWNERS_READY A=${owners.get('a')} B=${owners.get('b')} T=${owners.get('t')}`)
}

for (const [key, name, move] of roles) {
  const client = mc.createClient({ host: '127.0.0.1', port: key === 'a' ? 33153 : 33154, username: name, auth: 'offline', version: '26.3' })
  clients.set(key, client)
  let ready = false
  client.on('packet', (packet, meta) => {
    if (meta.name === 'system_chat') console.log(`SYSTEM_CHAT ${key} ${flatten(packet)}`)
    if (/window|container|slot|screen/i.test(meta.name)) console.log(`PACKET ${key}:${meta.name}`)
    if (meta.name === 'set_slot' && (packet.windowId === -1 || packet.slot === -1)) {
      console.log(`CURSOR ${key} ${JSON.stringify(packet)}`)
      console.log(`CURSOR_STATE ${key} count=${packet.item?.itemCount || 0}`)
    }

    if (meta.name === 'position') {
      client.write('teleport_confirm', { teleportId: packet.teleportId, x: packet.x, y: packet.y, z: packet.z, yRot: packet.yaw, xRot: packet.pitch })
      client.write('player_loaded', {})
      if (!ready) {
        ready = true
        setInterval(() => {
          if (!client.ended) {
            client.write('player_input', { inputs: {} })
            client.write('tick_end', {})
          }
        }, 50)
        console.log(`READY ${key} ${name}`)
        if (move) setTimeout(() => client.write('chat_command', { command: 'tp @s 16008 100 16008' }), 1200)
      }
      return
    }

    if (['action_bar', 'system_chat', 'disguised_chat'].includes(meta.name)) {
      const text = flatten(packet)
      const found = text.match(/race-a|race-b/)
      if (found) {
        const raw = JSON.stringify(packet)
        const local = /"aqua"/.test(raw), remote = /"red"/.test(raw)
        if (local || remote) {
          const owner = local ? found[0] : (found[0] === 'race-a' ? 'race-b' : 'race-a')
          if (owners.get(key) !== owner) {
            owners.set(key, owner)
            console.log(`OWNER ${key}=${owner}`)
            maybeReady()
          }
        }
      }
    }

    if (meta.name === 'open_window') {
      windows.set(key, packet.windowId)
      console.log(`WINDOW ${key} id=${packet.windowId}`)
    } else if (meta.name === 'window_items' && windows.get(key) === packet.windowId) {
      states.set(key, packet.stateId)
      snapshots.set(key, packet.items)
      console.log(`CARRIED ${key} state=${packet.stateId} count=${packet.carriedItem?.itemCount || 0}`)
      const items = packet.items.map((item, index) => item?.itemCount ? `${index}:${item.itemId}x${item.itemCount}` : null).filter(Boolean)
      console.log(`ITEMS ${key} state=${packet.stateId} ${items.join(',')}`)
    } else if (meta.name === 'set_slot' && windows.get(key) === packet.windowId) {
      states.set(key, packet.stateId)
      console.log(`SLOT ${key} state=${packet.stateId} slot=${packet.slot} ${packet.item?.itemCount ? `${packet.item.itemId}x${packet.item.itemCount}` : 'empty'}`)
    }
  })
  client.on('error', error => console.error(`ERROR ${key}: ${error.message}`))
  client.on('end', error => console.log(`END ${key}: ${error || ''}`))
}

const input = readline.createInterface({ input: process.stdin, output: process.stdout })
input.on('line', line => {
  const command = line.trim().toUpperCase()
  if (command === 'DEBUG') {
    for (const client of clients.values()) client.write('chat_command', { command: 'mpdebug' })
  } else if (command === 'SEED') {
    clients.get('b').write('chat_command', { command: 'give RC5PTarget minecraft:diamond 16' })
    console.log('SEED_SENT')
  } else if (command === 'OPEN') {
    for (const key of ['a', 'b']) clients.get(key).write('chat_command', { command: `${inventoryKind === 'ender' ? 'endersee' : 'invsee'} ${target}` })
    console.log('OPEN_SENT')
  } else if (command === 'CLICK') {
    const viewers = clickScenario === 'simultaneous' ? ['a', 'b'] : ['a']
    for (const key of viewers) {
      const client = clients.get(key), windowId = windows.get(key), stateId = states.get(key), stack = snapshots.get(key)?.[0]
      if (windowId == null || stateId == null || !stack?.itemCount) throw new Error(`No stack/window/state for ${key}`)
      const prediction = { itemId: stack.itemId, itemCount: stack.itemCount, components: [], removeComponents: [] }
      setTimeout(() => client.write('window_click', {
        windowId, stateId, slot: 0, mouseButton: 0, mode: 0,
        changedSlots: [{ location: 0, item: null }], cursorItem: prediction
      }), 50)
    }
    console.log(clickScenario === 'simultaneous' ? 'PROTOCOL_PREDICTED_SIMULTANEOUS_PICKUPS_SENT' : 'PROTOCOL_PREDICTED_PICKUP_SENT')
  } else if (command === 'CLOSE') {
    const viewers = clickScenario === 'simultaneous' ? ['a', 'b'] : ['a']
    for (const key of viewers) {
      const client = clients.get(key), windowId = windows.get(key)
      if (windowId != null) client.write('close_window', { windowId })
    }
    console.log('CLOSE_SENT')
  } else if (command === 'QUIT') {
    input.close()
    for (const client of clients.values()) client.end('disposable probe exit')
    setTimeout(() => process.exit(0), 500)
  }
})

setTimeout(() => console.log('OWNER_PROBE_TIMEOUT'), 60000)
