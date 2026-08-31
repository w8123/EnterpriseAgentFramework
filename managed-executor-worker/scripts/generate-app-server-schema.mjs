import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageJson = JSON.parse(await readFile(path.join(projectRoot, 'package.json'), 'utf8'));
const expectedVersion = packageJson.managedExecutor?.codexVersion;
if (typeof expectedVersion !== 'string' || !/^\d+\.\d+\.\d+$/.test(expectedVersion)) {
  throw new Error('package.json must declare managedExecutor.codexVersion');
}

const codexBinary = process.env.CODEX_BINARY || 'codex';
const versionInvocation = resolveCodexInvocation(codexBinary, ['--version']);
const version = spawnSync(versionInvocation.command, versionInvocation.args, {
  cwd: projectRoot,
  encoding: 'utf8',
  windowsHide: true,
});
if (version.status !== 0) throw new Error('Unable to execute the pinned Codex CLI');
const detectedVersion = /(?:codex-cli\s+)?(\d+\.\d+\.\d+)/.exec(version.stdout)?.[1];
if (detectedVersion !== expectedVersion) {
  throw new Error(`Codex CLI version mismatch: expected ${expectedVersion}, received ${detectedVersion || 'unknown'}`);
}

const generatedRoot = path.join(projectRoot, 'schemas', 'generated');
const outputDirectory = path.join(generatedRoot, expectedVersion);
assertGeneratedTarget(outputDirectory, generatedRoot);
await rm(outputDirectory, { recursive: true, force: true });
await mkdir(outputDirectory, { recursive: true });

const generationInvocation = resolveCodexInvocation(codexBinary, [
  'app-server',
  'generate-json-schema',
  '--out',
  outputDirectory,
]);
const generated = spawnSync(generationInvocation.command, generationInvocation.args, {
  cwd: projectRoot,
  encoding: 'utf8',
  windowsHide: true,
});
if (generated.status !== 0) throw new Error('Codex app-server schema generation failed');

const required = [
  'ClientRequest.json',
  'ServerRequest.json',
  'ServerNotification.json',
  'codex_app_server_protocol.schemas.json',
  'codex_app_server_protocol.v2.schemas.json',
];
for (const name of required) {
  const entry = await stat(path.join(outputDirectory, name)).catch(() => null);
  if (!entry?.isFile()) throw new Error(`Generated schema bundle is missing ${name}`);
}
const protocolBundle = (await Promise.all(required.map((name) => readFile(
  path.join(outputDirectory, name),
  'utf8',
)))).join('\n');
for (const requiredProtocolToken of [
  'thread/start',
  'turn/start',
  'turn/interrupt',
  'item/commandExecution/requestApproval',
  'item/fileChange/requestApproval',
  'workspaceWrite',
  'readOnly',
]) {
  if (!protocolBundle.includes(requiredProtocolToken)) {
    throw new Error(`Generated protocol no longer contains ${requiredProtocolToken}`);
  }
}

const files = await listFiles(outputDirectory);
const manifest = {
  schema: 'reachai.codex-app-server-schema-manifest.v1',
  codexVersion: expectedVersion,
  experimentalMethodsIncluded: false,
  files: await Promise.all(files.map(async (relativePath) => {
    const content = await readFile(path.join(outputDirectory, relativePath));
    return {
      path: relativePath.replaceAll('\\', '/'),
      bytes: content.length,
      sha256: createHash('sha256').update(content).digest('hex'),
    };
  })),
};
await writeFile(
  path.join(outputDirectory, 'reachai-schema-manifest.json'),
  `${JSON.stringify(manifest, null, 2)}\n`,
  'utf8',
);
process.stdout.write(`Generated ${files.length} non-experimental app-server schema files for Codex ${expectedVersion}.\n`);

async function listFiles(root) {
  const result = [];
  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      const absolutePath = path.join(directory, entry.name);
      if (entry.isDirectory()) await visit(absolutePath);
      if (entry.isFile()) result.push(path.relative(root, absolutePath));
    }
  }
  await visit(root);
  return result.sort((left, right) => left.localeCompare(right));
}

function assertGeneratedTarget(target, root) {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error('Refusing to replace a schema directory outside schemas/generated');
  }
}

function resolveCodexInvocation(requested, args) {
  if (/\.(?:c|m)?js$/i.test(requested)) {
    return { command: process.execPath, args: [requested, ...args] };
  }
  if (process.platform !== 'win32' || requested.toLowerCase().endsWith('.exe')) {
    return { command: requested, args };
  }
  const candidates = [];
  if (path.isAbsolute(requested)) {
    candidates.push(path.join(path.dirname(requested), 'node_modules', '@openai', 'codex', 'bin', 'codex.js'));
  } else {
    for (const entry of (process.env.PATH || process.env.Path || '').split(path.delimiter).filter(Boolean)) {
      candidates.push(path.join(entry, 'node_modules', '@openai', 'codex', 'bin', 'codex.js'));
    }
  }
  const script = candidates.find((candidate) => existsSync(candidate));
  if (script) return { command: process.execPath, args: [script, ...args] };
  if (requested.includes('/') || requested.includes('\\')) {
    throw new Error('Windows CODEX_BINARY must resolve to codex.js or a native executable');
  }
  return { command: requested, args };
}
