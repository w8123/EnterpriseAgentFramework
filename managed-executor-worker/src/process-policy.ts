import { spawn } from 'node:child_process';

export interface BoundedProcessOptions {
  cwd: string;
  env: NodeJS.ProcessEnv;
  timeoutMs: number;
  maxOutputBytes?: number;
}

export interface BoundedProcessResult {
  exitCode: number | null;
  signal: NodeJS.Signals | null;
  timedOut: boolean;
  outputLimitExceeded: boolean;
  stdout: string;
  stderr: string;
  durationMs: number;
}

const SAFE_INHERITED_ENV = [
  'PATH', 'Path', 'PATHEXT', 'SystemRoot', 'WINDIR', 'ComSpec',
  'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL', 'CODEX_HOME',
] as const;

export function buildWorkerEnvironment(source: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
  const environment: NodeJS.ProcessEnv = {};
  for (const name of SAFE_INHERITED_ENV) {
    const value = source[name];
    if (value !== undefined) environment[name] = value;
  }
  environment.CI = 'true';
  environment.NO_COLOR = '1';
  environment.FORCE_COLOR = '0';
  environment.GIT_TERMINAL_PROMPT = '0';
  environment.GIT_ASKPASS = process.platform === 'win32' ? 'cmd /c exit 1' : '/bin/false';
  environment.GIT_PAGER = 'cat';
  environment.GIT_CONFIG_NOSYSTEM = '1';
  environment.GIT_CONFIG_GLOBAL = process.platform === 'win32' ? 'NUL' : '/dev/null';
  return environment;
}

export async function runBoundedProcess(
  argv: string[],
  options: BoundedProcessOptions,
): Promise<BoundedProcessResult> {
  if (argv.length === 0 || !argv[0]) throw new Error('Process argv must not be empty');
  const maxOutputBytes = clamp(options.maxOutputBytes ?? 256 * 1024, 1_024, 32 * 1024 * 1024);
  const started = Date.now();
  let stdout = Buffer.alloc(0);
  let stderr = Buffer.alloc(0);
  let timedOut = false;
  let outputLimitExceeded = false;

  const child = spawn(argv[0], argv.slice(1), {
    cwd: options.cwd,
    env: options.env,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });

  const append = (current: Buffer, chunk: Buffer): Buffer => {
    if (current.length >= maxOutputBytes) {
      if (chunk.length > 0) outputLimitExceeded = true;
      return current;
    }
    const remaining = maxOutputBytes - current.length;
    if (chunk.length > remaining) outputLimitExceeded = true;
    return Buffer.concat([current, chunk.subarray(0, remaining)]);
  };
  child.stdout.on('data', (chunk: Buffer) => {
    stdout = append(stdout, chunk);
    if (outputLimitExceeded) child.kill('SIGKILL');
  });
  child.stderr.on('data', (chunk: Buffer) => {
    stderr = append(stderr, chunk);
    if (outputLimitExceeded) child.kill('SIGKILL');
  });

  const timer = setTimeout(() => {
    timedOut = true;
    child.kill('SIGKILL');
  }, clamp(options.timeoutMs, 1_000, 60 * 60_000));
  timer.unref?.();

  const result = await new Promise<{ exitCode: number | null; signal: NodeJS.Signals | null }>((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (exitCode, signal) => resolve({ exitCode, signal }));
  }).finally(() => clearTimeout(timer));

  return {
    ...result,
    timedOut,
    outputLimitExceeded,
    stdout: stdout.toString('utf8'),
    stderr: stderr.toString('utf8'),
    durationMs: Date.now() - started,
  };
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, Math.trunc(value)));
}
