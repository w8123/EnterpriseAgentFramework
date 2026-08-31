import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { resolveCodexInvocation } from './codex-command.js';
import { JsonRpcConnection, type JsonRpcConnectionOptions } from './json-rpc-connection.js';

export interface CodexAppServerProcessOptions extends JsonRpcConnectionOptions {
  codexBinary?: string;
  cwd: string;
  env?: NodeJS.ProcessEnv;
}

export class CodexAppServerProcess {
  readonly connection: JsonRpcConnection;
  private readonly process: ChildProcessWithoutNullStreams;
  private exited = false;

  private constructor(process: ChildProcessWithoutNullStreams, options: JsonRpcConnectionOptions) {
    this.process = process;
    this.connection = new JsonRpcConnection(process.stdout, process.stdin, options);
    process.stderr.resume();
    process.once('exit', (code, signal) => {
      this.exited = true;
      this.connection.close(new Error(
        `Codex app-server exited before shutdown (code=${code ?? 'null'}, signal=${signal ?? 'null'})`,
      ));
    });
    process.once('error', (error) => this.connection.close(error));
  }

  static start(options: CodexAppServerProcessOptions): CodexAppServerProcess {
    const invocation = resolveCodexInvocation(
      options.codexBinary,
      ['app-server', '--stdio', '--strict-config'],
      options.env,
    );
    const child = spawn(
      invocation.command,
      invocation.args,
      {
        cwd: options.cwd,
        env: options.env,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
      },
    );
    return new CodexAppServerProcess(child, {
      ...(options.requestTimeoutMs === undefined ? {} : { requestTimeoutMs: options.requestTimeoutMs }),
      ...(options.maxMessageBytes === undefined ? {} : { maxMessageBytes: options.maxMessageBytes }),
    });
  }

  async stop(graceMs = 2_000): Promise<void> {
    if (this.exited) return;
    this.connection.close(new Error('Managed Executor stopped Codex app-server'));
    this.process.kill('SIGTERM');
    await Promise.race([
      new Promise<void>((resolve) => this.process.once('exit', () => resolve())),
      new Promise<void>((resolve) => {
        setTimeout(resolve, Math.max(100, graceMs));
      }),
    ]);
    if (!this.exited) this.process.kill('SIGKILL');
  }
}
