import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { readFile } from 'node:fs/promises'
import { canonicalizeNuget } from './canonicalize-nuget.mjs'

const packagePath = new URL('../reachai-control-service/src/main/resources/ai-assist/artifacts/dotnet-sdk/ReachAI.AspNetCore.1.0.0.nupkg', import.meta.url)

test('published NuGet archive is already deterministic and valid on reinspection', async () => {
  const bytes = await readFile(packagePath)
  assert.deepEqual(canonicalizeNuget(bytes), bytes)
})

test('canonicalization rejects damaged ZIP content instead of recomputing its integrity', async () => {
  const bytes = await readFile(packagePath)
  const damaged = Buffer.from(bytes)
  const firstData = 30 + damaged.readUInt16LE(26) + damaged.readUInt16LE(28)
  damaged[firstData] ^= 1
  assert.throws(() => canonicalizeNuget(damaged), /checksum mismatch/)
  assert.throws(() => canonicalizeNuget(bytes.subarray(0, bytes.length - 1)), /Invalid/)
})
