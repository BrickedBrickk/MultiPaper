'use strict'

// Disposable-only end-to-end regression for remote InvSee++ Ender Chest edits.
// The clients must connect to separate backend routes (typically two local
// ViaProxy listeners); RCON must be loopback-only on both disposable backends.
// Required environment: MULTIPAPER_TEST_RCON_PASSWORD.
// Dependencies are loaded from --client-root, which must contain mineflayer.

const net = require('node:net')
const path = require('node:path')
const { createRequire } = require('node:module')

function parseArgs(argv) {
  const values = {
    'admin-host': '127.0.0.1',
    'target-host': '127.0.0.1',
    'rcon-host': '127.0.0.1',
    'admin-name': 'EnderAdminTest',
    'target-name': 'EnderTargetTest',
    'emeralds': '6'
  }
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i]
    if (!key.startsWith('--')) throw new Error(`Unexpected argument: ${key}`)
    if (key === '--disposable-fixture') {
      values[key.slice(2)] = true
      continue
    }
    if (i + 1 >= argv.length) throw new Error(`Missing value for ${key}`)
    values[key.slice(2)] = argv[++i]
  }
  return values
}

function requiredInteger(args, key, min = 1, max = 65535) {
  const value = Number(args[key])
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`--${key} must be an integer in [${min}, ${max}]`)
  }
  return value
}

function packet(id, type, body) {
  const payload = Buffer.from(body, 'utf8')
  const out = Buffer.alloc(payload.length + 14)
  out.writeInt32LE(payload.length + 10, 0)
  out.writeInt32LE(id, 4)
  out.writeInt32LE(type, 8)
  payload.copy(out, 12)
  out[out.length - 2] = 0
  out[out.length - 1] = 0
  return out
}

function rcon(host, port, password, command) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port })
    let buffer = Buffer.alloc(0)
    let authenticated = false
    let settled = false
    const timeout = setTimeout(() => finish(new Error(`RCON timeout on ${host}:${port}: ${command}`)), 10000)

    function finish(error, value) {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      socket.destroy()
      error ? reject(error) : resolve(value)
    }

    socket.on('connect', () => socket.write(packet(1, 3, password)))
    socket.on('error', error => finish(error))
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk])
      while (buffer.length >= 4) {
        const length = buffer.readInt32LE(0)
        if (length < 10 || length > 4110) return finish(new Error(`Invalid RCON packet length ${length}`))
        if (buffer.length < length + 4) return
        const frame = buffer.subarray(4, length + 4)
        buffer = buffer.subarray(length + 4)
        const id = frame.readInt32LE(0)
        const type = frame.readInt32LE(4)
        const body = frame.subarray(8, length - 2).toString('utf8')

        if (!authenticated && id === -1) return finish(new Error(`RCON auth rejected on ${host}:${port}`))
        if (!authenticated && id === 1 && type === 2) {
          authenticated = true
          socket.write(packet(2, 2, command))
        } else if (authenticated && id === 2 && (type === 0 || type === 2)) {
          finish(null, body)
        }
      }
    })
  })
}

function itemCount(nbtText, itemId) {
  const escaped = itemId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const expression = new RegExp(`id:\\s*"${escaped}",\\s*count:\\s*(\\d+)`, 'g')
  let total = 0
  for (const match of nbtText.matchAll(expression)) total += Number(match[1])
  return total
}

function waitFor(emitter, event, predicate = () => true, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => done(new Error(`Timed out waiting for ${event}`)), timeoutMs)
    function handler(...args) {
      if (predicate(...args)) done(null, ...args)
    }
    function done(error, ...args) {
      clearTimeout(timer)
      emitter.removeListener(event, handler)
      error ? reject(error) : resolve(args)
    }
    emitter.on(event, handler)
  })
}

async function waitUntil(label, check, timeoutMs = 15000) {
  const end = Date.now() + timeoutMs
  let lastError
  while (Date.now() < end) {
    try {
      const value = await check()
      if (value) return value
    } catch (error) {
      lastError = error
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error(`Timed out waiting for ${label}${lastError ? `: ${lastError.message}` : ''}`)
}

function connectClient(mineflayer, host, port, username) {
  const bot = mineflayer.createBot({ host, port, username, version: '26.1', auth: 'offline', checkTimeoutInterval: 30000 })
  let heartbeat
  bot.on('spawn', () => {
    heartbeat = setInterval(() => {
      if (bot._client?.state !== 'play') return
      bot._client.write('player_input', { inputs: {} })
      bot._client.write('tick_end', {})
    }, 50)
  })
  bot.on('end', () => clearInterval(heartbeat))
  bot.on('error', error => console.error(`CLIENT_ERROR ${username}: ${error.message}`))
  return bot
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (!args['disposable-fixture']) throw new Error('Refusing to run without --disposable-fixture')
  if (!args['client-root']) throw new Error('Pass --client-root with a Mineflayer dependency directory')
  const password = process.env.MULTIPAPER_TEST_RCON_PASSWORD
  if (!password) throw new Error('Set MULTIPAPER_TEST_RCON_PASSWORD for disposable loopback RCON')
  if (args['rcon-host'] !== '127.0.0.1' && args['rcon-host'] !== '::1') {
    throw new Error('--rcon-host must be loopback; use an SSH tunnel for remote disposable hosts')
  }

  const adminPort = requiredInteger(args, 'admin-port')
  const targetPort = requiredInteger(args, 'target-port')
  const adminRconPort = requiredInteger(args, 'admin-rcon-port')
  const targetRconPort = requiredInteger(args, 'target-rcon-port')
  let x = args['chest-x'] === undefined ? null : requiredInteger(args, 'chest-x', -30000000, 30000000)
  let y = args['chest-y'] === undefined ? null : requiredInteger(args, 'chest-y', -64, 320)
  let z = args['chest-z'] === undefined ? null : requiredInteger(args, 'chest-z', -30000000, 30000000)
  const suppliedCoordinates = [x, y, z].filter(value => value !== null).length
  if (suppliedCoordinates !== 0 && suppliedCoordinates !== 3) {
    throw new Error('Pass all of --chest-x/--chest-y/--chest-z or omit all three to place beside the target spawn')
  }
  const count = requiredInteger(args, 'emeralds', 1, 64)
  const adminName = String(args['admin-name'])
  const targetName = String(args['target-name'])
  for (const [label, name] of [['admin-name', adminName], ['target-name', targetName]]) {
    if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) throw new Error(`Invalid --${label}`)
  }
  if (adminName.toLowerCase() === targetName.toLowerCase()) throw new Error('Admin and target names must differ')

  const requireClient = createRequire(path.join(path.resolve(args['client-root']), 'package.json'))
  const mineflayer = requireClient('mineflayer')
  const Vec3 = requireClient('vec3').Vec3
  const rconHost = args['rcon-host']
  const adminRcon = command => rcon(rconHost, adminRconPort, password, command)
  const targetRcon = command => rcon(rconHost, targetRconPort, password, command)
  const emeraldId = 'minecraft:emerald'
  let admin
  let target
  let adminWindow = null
  let lastObservedState = null

  async function state() {
    const [ownerChest, adminMirrorChest, adminInventory, targetInventory] = await Promise.all([
      targetRcon(`data get entity ${targetName} EnderItems`),
      adminRcon(`data get entity ${targetName} EnderItems`),
      adminRcon(`data get entity ${adminName} Inventory`),
      targetRcon(`data get entity ${targetName} Inventory`)
    ])
    lastObservedState = {
      ownerChest: itemCount(ownerChest, emeraldId),
      adminMirrorChest: itemCount(adminMirrorChest, emeraldId),
      adminInventory: itemCount(adminInventory, emeraldId),
      targetInventory: itemCount(targetInventory, emeraldId)
    }
    return lastObservedState
  }

  async function assertState(label, expected) {
    try {
      return await waitUntil(label, async () => {
        const actual = await state()
        const matches = Object.keys(expected).every(key => actual[key] === expected[key])
        const total = actual.ownerChest + actual.adminInventory + actual.targetInventory
        if (matches && total === count) {
          console.log(`STATE ${label} ${JSON.stringify(actual)} total=${total}`)
          return actual
        }
        return false
      })
    } catch (error) {
      throw new Error(`${error.message}; last authoritative state=${JSON.stringify(lastObservedState)}`)
    }
  }

  try {
    admin = connectClient(mineflayer, args['admin-host'], adminPort, adminName)
    target = connectClient(mineflayer, args['target-host'], targetPort, targetName)
    await Promise.all([waitFor(admin, 'spawn'), waitFor(target, 'spawn')])

    // Keep the synthetic target at its normal spawn position. An RCON teleport
    // can trigger GrimAC movement/tick checks before the container interaction.
    if (x === null) {
      x = Math.floor(target.entity.position.x) + 2
      y = Math.floor(target.entity.position.y)
      z = Math.floor(target.entity.position.z)
    }
    console.log(`FIXTURE targetSpawn=${target.entity.position} enderChest=${x},${y},${z}`)

    await adminRcon(`op ${adminName}`)
    await adminRcon(`clear ${adminName} ${emeraldId}`)
    await targetRcon(`clear ${targetName} ${emeraldId}`)
    await targetRcon(`setblock ${x - 1} ${y - 1} ${z} stone`)
    await targetRcon(`setblock ${x} ${y - 1} ${z} stone`)
    await targetRcon(`setblock ${x} ${y} ${z} ender_chest`)
    await targetRcon(`give ${targetName} ${emeraldId} ${count}`)

    const chestBlock = await waitUntil('target Ender Chest block', () => {
      const block = target.blockAt(new Vec3(x, y, z))
      return block?.name === 'ender_chest' ? block : false
    })
    await target.lookAt(chestBlock.position.offset(0.5, 0.5, 0.5), true)
    const targetWindow = await target.openBlock(chestBlock)
    await waitUntil('seed emeralds in target player inventory', () => targetWindow.slots[54]?.name === 'emerald' && targetWindow.slots[54]?.count === count)
    await target.moveSlotItem(54, 0)
    await assertState('target deposits into owner Ender Chest', {
      ownerChest: count, adminMirrorChest: count, adminInventory: 0, targetInventory: 0
    })
    target.closeWindow(targetWindow)

    const adminWindowPromise = waitFor(admin, 'windowOpen', window => {
      const title = JSON.stringify(window.title || '').toLowerCase()
      return title.includes(targetName.toLowerCase()) || window.slots.length >= 63
    })
    admin.chat(`/endersee ${targetName}`)
    const [openedWindow] = await adminWindowPromise
    adminWindow = openedWindow
    await waitUntil('remote Ender Chest stack visible', () => adminWindow.slots[0]?.name === 'emerald' && adminWindow.slots[0]?.count === count)

    await admin.moveSlotItem(0, 54)
    await assertState('remote withdrawal', {
      ownerChest: 0, adminMirrorChest: 0, adminInventory: count, targetInventory: 0
    })

    await admin.moveSlotItem(54, 0)
    await assertState('remote redeposit', {
      ownerChest: count, adminMirrorChest: count, adminInventory: 0, targetInventory: 0
    })

    await admin.moveSlotItem(0, 54)
    await assertState('second remote withdrawal', {
      ownerChest: 0, adminMirrorChest: 0, adminInventory: count, targetInventory: 0
    })

    console.log(`PASS: ${count} emeralds conserved through target deposit and two cross-backend InvSee++ withdrawal cycles.`)
  } finally {
    try { if (adminWindow && admin?.currentWindow) admin.closeWindow(adminWindow) } catch {}
    try { admin?.quit('disposable ender chest regression complete') } catch {}
    try { target?.quit('disposable ender chest regression complete') } catch {}
  }
}

main().catch(error => {
  console.error(`FAIL: ${error.stack || error.message}`)
  process.exitCode = 1
})
