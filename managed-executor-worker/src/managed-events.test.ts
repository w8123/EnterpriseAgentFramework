import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';
import { ManagedEventProjector } from './managed-events.js';

const workspace = path.resolve('C:/sandbox/workspace');

test('drops raw reasoning and command output deltas', () => {
  const projector = fixedProjector();
  assert.deepEqual(projector.projectNotification({
    method: 'item/reasoning/textDelta',
    params: { delta: 'private reasoning' },
  }), []);
  assert.deepEqual(projector.projectNotification({
    method: 'item/commandExecution/outputDelta',
    params: { delta: 'password=raw-secret' },
  }), []);
  assert.deepEqual(projector.projectNotification({
    method: 'item/started',
    params: { item: { type: 'reasoning', text: 'private reasoning' } },
  }), []);
});

test('redacts secrets and paths in approval events', () => {
  const projector = fixedProjector();
  const [event] = projector.projectServerRequest({
    id: 7,
    method: 'item/commandExecution/requestApproval',
    params: {
      threadId: 'thr-1',
      turnId: 'turn-1',
      itemId: 'item-1',
      reason: 'needs Authorization: Bearer abcdefghijklmnop',
      command: ['tool', '--api-key=sk-abcdefghijklmnopqrstuvwxyz'],
      cwd: path.join(workspace, 'module'),
      networkApprovalContext: { host: 'registry.example.com', protocol: 'https' },
    },
  });

  assert.ok(event);
  assert.equal(event.type, 'APPROVAL_REQUESTED');
  assert.equal(event.phase, 'WAITING_APPROVAL');
  assert.equal(event.sequence, 1);
  assert.equal(event.eventId, 'mex-test:1');
  assert.equal(event.data.cwd, '$WORKSPACE/module');
  assert.deepEqual(event.data.command, ['tool', '--api-key=<redacted>']);
  assert.doesNotMatch(JSON.stringify(event), /abcdefghijklmnop|sk-abcdefghijklmnopqrstuvwxyz/);
});

test('projects only bounded final text and deterministic completion evidence', () => {
  const projector = fixedProjector();
  const [message] = projector.projectNotification({
    method: 'item/completed',
    params: {
      threadId: 'thr-1',
      turnId: 'turn-1',
      item: {
        id: 'item-final',
        type: 'agentMessage',
        text: 'Finished. password=hunter2',
      },
    },
  });
  const [completed] = projector.projectNotification({
    method: 'turn/completed',
    params: { threadId: 'thr-1', turn: { id: 'turn-1', status: 'completed' } },
  });

  assert.ok(message);
  assert.equal(message.type, 'FINAL_MESSAGE');
  assert.equal(message.visibility, 'PUBLIC');
  assert.equal(message.data.text, 'Finished. password=<redacted>');
  assert.ok(completed);
  assert.equal(completed.phase, 'SUCCEEDED');
  assert.equal(completed.sequence, 2);
});

test('hides absolute paths outside the workspace', () => {
  const projector = fixedProjector();
  const [event] = projector.projectNotification({
    method: 'item/started',
    params: {
      item: {
        id: 'item-1',
        type: 'commandExecution',
        command: ['git', 'status'],
        cwd: path.resolve('C:/other-tenant/repository'),
      },
    },
  });
  assert.ok(event);
  assert.equal(event.data.cwd, '<external-path>');
});

function fixedProjector(): ManagedEventProjector {
  return new ManagedEventProjector({
    executionId: 'mex-test',
    workspaceRoot: workspace,
    clock: () => new Date('2026-08-24T00:00:00.000Z'),
  });
}
