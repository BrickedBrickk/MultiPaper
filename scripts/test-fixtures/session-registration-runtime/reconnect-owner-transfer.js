'use strict'

require('../../Minecraft26_3ProtocolSupport.js')
const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const backendA = Number(process.argv[3] || 33153)
const backendB = Number(process.argv[4] || 33154)
// Minecraft profile names are limited to 16 characters; an overlong name makes
// the backend reject the hello packet before login and produces a misleading
// packet-decoder failure.
const username = process.argv[5] || 'RC5Reconnect'

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function loginAndDisconnect(port, label) {
  await new Promise((resolve, reject) => {
    const client = mc.createClient({ host, port, username, auth: 'offline', version: '26.3' })
    let gotPosition = false
    let requestedEnd = false
    let ticker = null
    const timeout = setTimeout(() => {
      requestedEnd = true
      client.end(`timeout at ${label}`)
      reject(new Error(`Timed out waiting for login position: ${label}`))
    }, 30000)

    client.on('error', error => {
      if (!requestedEnd) {
        clearTimeout(timeout)
        if (ticker) clearInterval(ticker)
        reject(error)
      }
    })

    client.on('packet', (packet, meta) => {
      if (meta.name !== 'position' || gotPosition) return
      gotPosition = true
      client.write('teleport_confirm', {
        teleportId: packet.teleportId,
        x: packet.x,
        y: packet.y,
        z: packet.z,
        yRot: packet.yaw,
        xRot: packet.pitch
      })
      client.write('player_loaded', {})
      console.log(`LOGIN_READY label=${label} username=${username} port=${port}`)
      ticker = setInterval(() => {
        if (!client.ended) {
          client.write('player_input', { inputs: {} })
          client.write('tick_end', {})
        }
      }, 50)
      setTimeout(() => {
        requestedEnd = true
        clearTimeout(timeout)
        if (ticker) clearInterval(ticker)
        console.log(`DISCONNECT_REQUEST label=${label}`)
        client.end(`disconnect after ${label}`)
      }, 1500)
    })

    client.once('end', () => {
      clearTimeout(timeout)
      if (ticker) clearInterval(ticker)
      if (!gotPosition) reject(new Error(`Connection ended before login: ${label}`))
      else resolve()
    })
  })
}

async function main() {
  await loginAndDisconnect(backendA, 'A_INITIAL')
  await sleep(2500)
  await loginAndDisconnect(backendA, 'A_RECONNECT')
  await sleep(2500)
  await loginAndDisconnect(backendB, 'B_OWNER_TRANSFER')
  await sleep(1500)
  console.log('SESSION_RECONNECT_SEQUENCE_COMPLETE')
}

main().catch(error => {
  console.error(error.stack || error)
  process.exitCode = 1
})
