'use strict'

// Protocol-777 smoke for login, player-scoped MultiPaper commands, and status ping.
// Seed the probe player as an operator on both disposable backends before testing /tps and /mspt.
// /slist is expected to work without operator status.
const mc = require('minecraft-protocol')

const [host = '127.0.0.1', portText = '25565', username = 'CommandSmoke', version = '26.3'] = process.argv.slice(2)
const port = Number(portText)
if (!Number.isInteger(port) || port < 1 || port > 65535 || username.length > 16) {
  console.error('Usage: node scripts/Run-ProxyCommandProbe.js [host [port [username [version]]]]')
  process.exit(2)
}

const expected = new Set(['list', 'tps', 'mspt', 'slist', 'mpdebug', 'version'])
const responses = new Map()
let ended = false
let commandsStarted = false
let chunks = 0

function flatten(value) {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map(flatten).join(' ')
  if (!value || typeof value !== 'object') return ''
  return Object.values(value).map(flatten).filter(Boolean).join(' ')
}

function isUnknownCommand(value) {
  return /command\.unknown\.command/i.test(value || '')
}

function finish() {
  if (ended) return
  ended = true
  clearTimeout(timeout)
  const missing = [...expected].filter(command => !responses.has(command))
  const slist = responses.get('slist') || ''
  const checks = [
    ['login and chunk delivery', chunks > 0],
    ['/list', responses.get('list')?.includes('There are') && !isUnknownCommand(responses.get('list'))],
    ['/tps', /TPS from last/i.test(responses.get('tps') || '') && !isUnknownCommand(responses.get('tps'))],
    ['/mspt', /Server tick times|MSPT/i.test(responses.get('mspt') || '') && !isUnknownCommand(responses.get('mspt'))],
    ['/slist reports both backends', slist.includes('smoke-a') && slist.includes('smoke-b') && !isUnknownCommand(slist)],
    ['/mpdebug', (responses.get('mpdebug') || '').includes('MultiPaper debug enabled') && !isUnknownCommand(responses.get('mpdebug'))],
    ['/version', (responses.get('version') || '').includes('This server is running MultiPaper version') && !isUnknownCommand(responses.get('version'))]
  ]
  const failed = checks.filter(([, pass]) => !pass)
  console.log(`COMMAND_RESPONSES ${JSON.stringify(Object.fromEntries(responses))}`)
  if (missing.length || failed.length) {
    console.error(`FAIL: missing responses [${missing.join(', ')}]; failed checks [${failed.map(([label]) => label).join(', ')}]`)
    client.end('command probe failed')
    process.exitCode = 1
  } else {
    console.log('PASS: proxy login, chunk delivery, /list, /tps, /mspt, /slist, /mpdebug, and /version.')
    client.end('command probe complete')
  }
}

let commandIndex = 0
const commands = ['list', 'tps', 'mspt', 'slist', 'mpdebug', 'version']
function sendNextCommand() {
  if (ended || commandIndex >= commands.length) return
  const command = commands[commandIndex++]
  client.write('chat_command', { command })
  console.log(`COMMAND ${command}`)
  setTimeout(sendNextCommand, 1800)
}

const client = mc.createClient({ host, port, username, auth: 'offline', version })
client.on('packet', (packet, meta) => {
  if (meta.name === 'position') {
    client.write('teleport_confirm', { teleportId: packet.teleportId, x: packet.x, y: packet.y, z: packet.z, yRot: packet.yaw, xRot: packet.pitch })
    client.write('player_loaded', {})
    if (!commandsStarted) {
      commandsStarted = true
      console.log(`PLAYER_READY ${packet.x},${packet.y},${packet.z}`)
      setTimeout(sendNextCommand, 1200)
    }
  } else if (meta.name === 'map_chunk') {
    chunks++
  } else if (meta.name === 'system_chat' || meta.name === 'disguised_chat') {
    const response = flatten(packet)
    for (const command of commands) {
      if (command === 'list' && response.includes('There are')) responses.set(command, response)
      if (command === 'tps' && !isUnknownCommand(response) && /TPS from last/i.test(response)) responses.set(command, response)
      if (command === 'mspt' && !isUnknownCommand(response) && /Server tick times/i.test(response)) responses.set(command, response)
      if (command === 'slist' && (response.includes('smoke-a') || response.includes('smoke-b'))) {
        responses.set(command, `${responses.get(command) || ''} ${response}`)
      }
      if (command === 'mpdebug' && response.includes('MultiPaper debug enabled')) responses.set(command, response)
      if (command === 'version' && response.includes('This server is running MultiPaper version')) responses.set(command, response)
    }
  } else if (meta.name === 'disconnect') {
    console.error(`DISCONNECT ${JSON.stringify(packet)}`)
  }
})

client.on('error', error => {
  console.error(`ERROR ${error.message}`)
  finish()
})
const timeout = setTimeout(finish, 24000)
