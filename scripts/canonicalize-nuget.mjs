import { readFile, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { inflateRawSync } from 'node:zlib'

// NuGet's ZIP metadata and generated XML newlines vary by OS, even for an
// identical deterministic DLL. Re-emit ordinary, unsigned packages as a
// deterministic ZIP_STORED archive with canonical XML newlines.
const crcTable = Array.from({ length: 256 }, (_, value) => {
  let crc = value
  for (let bit = 0; bit < 8; bit++) crc = (crc & 1) ? (0xedb88320 ^ (crc >>> 1)) : (crc >>> 1)
  return crc >>> 0
})
function crc32(bytes) {
  let crc = 0xffffffff
  for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8)
  return (crc ^ 0xffffffff) >>> 0
}

export function canonicalizeNuget(archive) {
  let end = -1
  for (let i = archive.length - 22; i >= Math.max(0, archive.length - 65557); i--) {
    if (archive.readUInt32LE(i) === 0x06054b50 && i + 22 + archive.readUInt16LE(i + 20) === archive.length) {
      end = i
      break
    }
  }
  if (end < 0 || archive.readUInt16LE(end + 4) !== 0 || archive.readUInt16LE(end + 6) !== 0) {
    throw new Error('Invalid or multipart NuGet archive')
  }
  const count = archive.readUInt16LE(end + 10)
  if (count !== archive.readUInt16LE(end + 8) || count === 0xffff) throw new Error('Unsupported ZIP64 NuGet archive')
  let offset = archive.readUInt32LE(end + 16)
  const entries = []
  const names = new Set()
  for (let i = 0; i < count; i++) {
    if (offset + 46 > end || archive.readUInt32LE(offset) !== 0x02014b50) throw new Error('Invalid ZIP directory')
    const flags = archive.readUInt16LE(offset + 8)
    const method = archive.readUInt16LE(offset + 10)
    const crc = archive.readUInt32LE(offset + 16)
    const compressed = archive.readUInt32LE(offset + 20)
    const uncompressed = archive.readUInt32LE(offset + 24)
    const nameLength = archive.readUInt16LE(offset + 28)
    const extraLength = archive.readUInt16LE(offset + 30)
    const commentLength = archive.readUInt16LE(offset + 32)
    const localOffset = archive.readUInt32LE(offset + 42)
    const name = archive.subarray(offset + 46, offset + 46 + nameLength).toString('utf8')
    if ((flags & 1) || ![0, 8].includes(method) || compressed === 0xffffffff || uncompressed === 0xffffffff ||
        !name || name === '.signature.p7s' || name.startsWith('/') || name.includes('\\') ||
        name.split('/').includes('..') || names.has(name)) {
      throw new Error('Unsupported or unsafe NuGet entry')
    }
    names.add(name)
    if (localOffset + 30 > archive.length || archive.readUInt32LE(localOffset) !== 0x04034b50) throw new Error('Invalid ZIP entry')
    const dataOffset = localOffset + 30 + archive.readUInt16LE(localOffset + 26) + archive.readUInt16LE(localOffset + 28)
    if (dataOffset + compressed > archive.length) throw new Error('Truncated NuGet entry')
    const packed = archive.subarray(dataOffset, dataOffset + compressed)
    let data = method === 8 ? inflateRawSync(packed) : Buffer.from(packed)
    if (data.length !== uncompressed || crc32(data) !== crc) throw new Error('NuGet entry checksum mismatch')
    if (/\.(xml|nuspec|rels|psmdcp)$/i.test(name)) {
      // All NuGet-generated XML is UTF-8; replacing only CRLF preserves all other bytes.
      const normalized = []
      for (let j = 0; j < data.length; j++) {
        if (data[j] === 13 && data[j + 1] === 10) continue
        normalized.push(data[j])
      }
      data = Buffer.from(normalized)
    }
    entries.push({ name, data })
    offset += 46 + nameLength + extraLength + commentLength
  }
  entries.sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
  const localRecords = []
  const centralRecords = []
  let position = 0
  for (const { name, data } of entries) {
    const nameBytes = Buffer.from(name, 'utf8')
    const crc = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6)
    local.writeUInt16LE(0, 8); local.writeUInt16LE(0, 10); local.writeUInt16LE(0x21, 12)
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBytes.length, 26)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x800, 8); central.writeUInt16LE(0, 10); central.writeUInt16LE(0, 12)
    central.writeUInt16LE(0x21, 14); central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24); central.writeUInt16LE(nameBytes.length, 28)
    central.writeUInt32LE(position, 42)
    localRecords.push(local, nameBytes, data)
    centralRecords.push(central, nameBytes)
    position += local.length + nameBytes.length + data.length
  }
  const directory = Buffer.concat(centralRecords)
  const trailer = Buffer.alloc(22)
  trailer.writeUInt32LE(0x06054b50, 0); trailer.writeUInt16LE(entries.length, 8)
  trailer.writeUInt16LE(entries.length, 10); trailer.writeUInt32LE(directory.length, 12)
  trailer.writeUInt32LE(position, 16)
  return Buffer.concat([...localRecords, directory, trailer])
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 4) throw new Error('Usage: node canonicalize-nuget.mjs <input.nupkg> <output.nupkg>')
  await writeFile(process.argv[3], canonicalizeNuget(await readFile(process.argv[2])))
}
