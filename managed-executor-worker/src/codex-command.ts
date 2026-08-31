import { existsSync } from 'node:fs';
import path from 'node:path';

export interface CodexInvocation {
  command: string;
  args: string[];
}

export function resolveCodexInvocation(
  codexBinary: string | undefined,
  args: string[],
  env: NodeJS.ProcessEnv = process.env,
): CodexInvocation {
  const requested = codexBinary || 'codex';
  if (/\.(?:c|m)?js$/i.test(requested)) {
    return { command: process.execPath, args: [requested, ...args] };
  }
  if (process.platform !== 'win32') return { command: requested, args };
  if (requested.toLowerCase().endsWith('.exe')) return { command: requested, args };

  const script = findWindowsCodexScript(requested, env);
  if (script) return { command: process.execPath, args: [script, ...args] };
  if (hasPathSeparator(requested)) {
    throw new Error('Windows codexBinary must resolve to codex.js or a native executable');
  }
  return { command: requested, args };
}

function findWindowsCodexScript(requested: string, env: NodeJS.ProcessEnv): string | null {
  const candidates: string[] = [];
  if (path.isAbsolute(requested)) {
    candidates.push(path.join(path.dirname(requested), 'node_modules', '@openai', 'codex', 'bin', 'codex.js'));
  } else {
    const pathValue = env.PATH || env.Path || '';
    for (const entry of pathValue.split(path.delimiter).filter(Boolean)) {
      candidates.push(path.join(entry, 'node_modules', '@openai', 'codex', 'bin', 'codex.js'));
    }
  }
  return candidates.find((candidate) => existsSync(candidate)) || null;
}

function hasPathSeparator(value: string): boolean {
  return value.includes('/') || value.includes('\\');
}
