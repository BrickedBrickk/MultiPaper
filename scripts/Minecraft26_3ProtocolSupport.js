'use strict'

// Compatibility shim for minecraft-protocol 1.68 with the Minecraft 26.3
// protocol data. The protocol data currently lives in a separate checkout and
// its entityDelta native type is not yet implemented by that dependency.

const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')

const version = '26.3'
const dataDir = process.env.MULTIPAPER_MC_DATA_26_3
if (!dataDir) {
  throw new Error('Set MULTIPAPER_MC_DATA_26_3 to the Minecraft 26.3 protocol data directory.')
}

const protocolEntry = require.resolve('minecraft-protocol')
const protocolRequire = createRequire(protocolEntry)
const mcDataEntry = protocolRequire.resolve('minecraft-data')
const mcDataRoot = path.dirname(mcDataEntry)
const dataPaths = require(path.join(mcDataRoot, 'data.js'))
const protocolVersionsPath = path.join(mcDataRoot, 'minecraft-data', 'data', 'pc', 'common', 'protocolVersions.json')
const protocolVersions = require(protocolVersionsPath)
const release = protocolVersions.find(entry => entry.minecraftVersion === version)
const externalData = path.resolve(dataDir)
const base = dataPaths.pc['26.1']

if (!release || !base || !fs.existsSync(path.join(externalData, 'protocol.json')) || !fs.existsSync(path.join(externalData, 'proto.yml'))) {
  throw new Error(`Incomplete Minecraft ${version} protocol data: ${externalData}`)
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

const compilerTypesPath = path.join(path.dirname(protocolEntry), 'datatypes', 'compiler-minecraft.js')
const compilerTypes = require(compilerTypesPath)
const [readVarInt, writeVarInt, sizeOfVarInt] = protocolRequire('protodef').types.varint
const PartialReadError = protocolRequire('protodef').utils.PartialReadError

compilerTypes.Read.entityDelta = ['native', (buffer, offset) => {
  const header = readVarInt(buffer, offset)
  const encodedCount = header.value >>> 1
  const implicitSingleStep = encodedCount === 0
  const count = implicitSingleStep ? 1 : encodedCount
  let cursor = offset + header.size
  const steps = []
  for (let i = 0; i < count; i++) {
    let ticks = 0
    if (!implicitSingleStep) {
      const encodedTicks = readVarInt(buffer, cursor)
      ticks = encodedTicks.value
      cursor += encodedTicks.size
    }
    if (cursor + 6 > buffer.length) throw new PartialReadError()
    steps.push({
      ticks,
      dX: buffer.readInt16BE(cursor),
      dY: buffer.readInt16BE(cursor + 2),
      dZ: buffer.readInt16BE(cursor + 4)
    })
    cursor += 6
  }
  return { value: { onGround: (header.value & 1) !== 0, steps }, size: cursor - offset }
}]

compilerTypes.Write.entityDelta = ['native', (value, buffer, offset) => {
  const steps = value.steps || []
  const implicitSingleStep = steps.length === 1 && steps[0].ticks === 0
  const encodedCount = implicitSingleStep ? 0 : steps.length
  offset = writeVarInt((encodedCount << 1) | (value.onGround ? 1 : 0), buffer, offset)
  for (const step of steps) {
    if (!implicitSingleStep) offset = writeVarInt(step.ticks, buffer, offset)
    buffer.writeInt16BE(step.dX, offset)
    buffer.writeInt16BE(step.dY, offset + 2)
    buffer.writeInt16BE(step.dZ, offset + 4)
    offset += 6
  }
  return offset
}]

compilerTypes.SizeOf.entityDelta = ['native', value => {
  const steps = value.steps || []
  const implicitSingleStep = steps.length === 1 && steps[0].ticks === 0
  let size = sizeOfVarInt(((implicitSingleStep ? 0 : steps.length) << 1) | (value.onGround ? 1 : 0))
  for (const step of steps) {
    if (!implicitSingleStep) size += sizeOfVarInt(step.ticks)
    size += 6
  }
  return size
}]

module.exports = { mapped, version }
