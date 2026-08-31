import path from 'node:path';
import { CodexAppServerProcess } from './codex-app-server-process.js';
import type { ApprovalBroker } from './approval.js';
import {
  buildEvidenceManifest,
  collectEvidence,
  EVIDENCE_MANIFEST_FILE,
  EVENT_LOG_FILE,
  EXECUTION_SUMMARY_FILE,
  PATCH_FILE,
  prepareOutputDirectory,
  TEST_REPORT_FILE,
  writeJsonArtifact,
} from './evidence.js';
import type { LoadedManagedExecutorJob } from './job-config.js';
import type { ManagedExecutionEventV1 } from './managed-events.js';
import { NdjsonEventSink } from './ndjson-event-sink.js';
import { buildWorkerEnvironment } from './process-policy.js';
import { ManagedExecutorRunner, type ManagedEventSink } from './runner.js';
import { ManagedEventSanitizer } from './sanitizer.js';

export interface ExecutionSummaryV1 {
  schema: 'reachai.managed-executor.execution-summary.v1';
  executionId: string;
  startedAt: string;
  completedAt: string;
  outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  verificationOutcome: 'PASSED' | 'FAILED' | 'NOT_RUN';
  readonlyViolation: boolean;
  appServer: {
    threadId: string | null;
    turnId: string | null;
    status: string | null;
  };
  patch: {
    baseCommit: string;
    empty: boolean;
    bytes: number;
    sha256: string;
  } | null;
  error: string | null;
}

export interface ManagedJobArtifactFile {
  artifactType: 'PATCH' | 'TEST_REPORT' | 'EXECUTION_SUMMARY' | 'EVIDENCE_MANIFEST' | 'EVENT_LOG';
  name: string;
  mediaType: string;
}

export interface ExecuteManagedJobOptions {
  eventSink?: ManagedEventSink;
  signal?: AbortSignal;
  approvalBroker?: ApprovalBroker;
}

export interface ExecuteManagedJobResult {
  exitCode: 0 | 1;
  summary: ExecutionSummaryV1;
  artifactFiles: ManagedJobArtifactFile[];
  workerResult: {
    schema: 'reachai.managed-executor.worker-result.v1';
    executionId: string;
    outcome: ExecutionSummaryV1['outcome'];
    verificationOutcome: ExecutionSummaryV1['verificationOutcome'];
  };
}

export async function executeManagedJob(
  job: LoadedManagedExecutorJob,
  options: ExecuteManagedJobOptions = {},
): Promise<ExecuteManagedJobResult> {
  await prepareOutputDirectory(job.outputDirectory);
  const sanitizer = new ManagedEventSanitizer({ workspaceRoot: job.workspaceRoot });
  const startedAt = new Date().toISOString();
  const localSink = await NdjsonEventSink.create(path.join(job.outputDirectory, EVENT_LOG_FILE));
  const sink = options.eventSink ? compositeSink(localSink, options.eventSink) : localSink;
  let appServer: CodexAppServerProcess | null = null;
  let runResult: { threadId: string; turnId: string; status: string } | null = null;
  let runError: Error | null = null;

  try {
    appServer = CodexAppServerProcess.start({
      cwd: job.workspaceRoot,
      env: buildWorkerEnvironment(),
      ...(job.codexBinary ? { codexBinary: job.codexBinary } : {}),
    });
    runResult = await new ManagedExecutorRunner(
      appServer.connection,
      sink,
      options.approvalBroker,
    ).run({
      executionId: job.executionId,
      workspaceRoot: job.workspaceRoot,
      objective: job.objective,
      sandboxProfile: job.sandboxProfile,
      maxWallTimeMs: job.maxWallTimeMs,
      approvalTimeoutMs: job.approvalTimeoutMs,
      ...(job.model ? { model: job.model } : {}),
      ...(options.signal ? { signal: options.signal } : {}),
    });
  } catch (error) {
    runError = error instanceof Error ? error : new Error('Managed Executor run failed');
  } finally {
    if (appServer) await appServer.stop().catch(() => undefined);
    await localSink.close();
  }

  const completedTurn = runResult?.status.toLowerCase() === 'completed';
  let evidence: Awaited<ReturnType<typeof collectEvidence>> | null = null;
  try {
    evidence = await collectEvidence(job, { runAcceptance: completedTurn && !options.signal?.aborted });
  } catch (error) {
    runError ??= error instanceof Error ? error : new Error('Evidence collection failed');
  }

  const verificationOutcome = evidence?.tests.outcome ?? 'NOT_RUN';
  const succeeded = completedTurn
    && !runError
    && evidence !== null
    && !evidence.readonlyViolation
    && verificationOutcome !== 'FAILED';
  const cancelled = options.signal?.aborted === true;
  const summary: ExecutionSummaryV1 = {
    schema: 'reachai.managed-executor.execution-summary.v1',
    executionId: job.executionId,
    startedAt,
    completedAt: new Date().toISOString(),
    outcome: succeeded ? 'SUCCEEDED' : cancelled ? 'CANCELLED' : 'FAILED',
    verificationOutcome,
    readonlyViolation: evidence?.readonlyViolation ?? false,
    appServer: {
      threadId: runResult?.threadId ?? null,
      turnId: runResult?.turnId ?? null,
      status: runResult?.status ?? null,
    },
    patch: evidence ? evidence.patch : null,
    error: runError ? sanitizer.text(safeExecutionError(runError)) : null,
  };
  await writeJsonArtifact(path.join(job.outputDirectory, EXECUTION_SUMMARY_FILE), summary);

  const manifestFiles = [
    { name: EVENT_LOG_FILE, mediaType: 'application/x-ndjson' },
    { name: EXECUTION_SUMMARY_FILE, mediaType: 'application/json' },
    ...(evidence ? [
      { name: PATCH_FILE, mediaType: 'text/x-diff' },
      { name: TEST_REPORT_FILE, mediaType: 'application/json' },
    ] : []),
  ];
  const manifest = await buildEvidenceManifest(job.executionId, job.outputDirectory, manifestFiles);
  await writeJsonArtifact(path.join(job.outputDirectory, EVIDENCE_MANIFEST_FILE), manifest);

  const artifactFiles: ManagedJobArtifactFile[] = [
    { artifactType: 'EVENT_LOG', name: EVENT_LOG_FILE, mediaType: 'application/x-ndjson' },
    { artifactType: 'EXECUTION_SUMMARY', name: EXECUTION_SUMMARY_FILE, mediaType: 'application/json' },
    { artifactType: 'EVIDENCE_MANIFEST', name: EVIDENCE_MANIFEST_FILE, mediaType: 'application/json' },
    ...(evidence ? [
      { artifactType: 'PATCH' as const, name: PATCH_FILE, mediaType: 'text/x-diff' },
      { artifactType: 'TEST_REPORT' as const, name: TEST_REPORT_FILE, mediaType: 'application/json' },
    ] : []),
  ];
  return {
    exitCode: succeeded ? 0 : 1,
    summary,
    artifactFiles,
    workerResult: {
      schema: 'reachai.managed-executor.worker-result.v1',
      executionId: job.executionId,
      outcome: summary.outcome,
      verificationOutcome: summary.verificationOutcome,
    },
  };
}

function compositeSink(first: ManagedEventSink, second: ManagedEventSink): ManagedEventSink {
  return {
    async emit(event: ManagedExecutionEventV1): Promise<void> {
      await first.emit(event);
      await second.emit(event);
    },
  };
}

function safeExecutionError(error: Error): string {
  const message = error.message.toLowerCase();
  if (message.includes('wall-time')) return 'Managed Executor wall-time limit exceeded';
  if (message.includes('cancel')) return 'Managed Executor execution cancelled';
  if (message.includes('approval')) return 'Managed Executor approval failed or timed out';
  if (message.includes('workspace patch') || message.includes('evidence') || message.includes('git')) {
    return 'Managed Executor evidence collection failed';
  }
  if (message.includes('app-server') || message.includes('json-rpc') || message.includes('codex')) {
    return 'Codex app-server execution failed';
  }
  return 'Managed Executor execution failed';
}
