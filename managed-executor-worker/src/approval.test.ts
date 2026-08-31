import assert from 'node:assert/strict';
import test from 'node:test';
import { RuntimeApprovalBroker } from './approval.js';
import type { JsonRpcRequest } from './protocol.js';

const request: JsonRpcRequest = {
  id: 'approval-1',
  method: 'item/commandExecution/requestApproval',
  params: {},
};

test('returns only a matching one-shot Runtime approval decision', async () => {
  let calls = 0;
  const broker = new RuntimeApprovalBroker({
    async commands(afterSequence) {
      calls += 1;
      if (calls === 1) {
        return {
          cursor: afterSequence + 1,
          commands: [{
            sequence: afterSequence + 1,
            type: 'APPROVAL_DECISION',
            data: { approvalRequestId: 'another-approval', decision: 'accept' },
          }],
        };
      }
      return {
        cursor: afterSequence + 1,
        commands: [{
          sequence: afterSequence + 1,
          type: 'APPROVAL_DECISION',
          data: { approvalRequestId: 'approval-1', decision: 'accept' },
        }],
      };
    },
  }, 50);

  assert.equal(await broker.decide(request, new AbortController().signal), 'accept');
  assert.equal(calls, 2);
});

test('fails closed on malformed, unavailable, cancellation and aborted command channels', async () => {
  const unavailable = new RuntimeApprovalBroker({
    async commands() {
      throw new Error('Bearer secret-should-not-leak');
    },
  }, 50);
  assert.equal(await unavailable.decide(request, new AbortController().signal), 'cancel');

  const cancelled = new RuntimeApprovalBroker({
    async commands(afterSequence) {
      return {
        cursor: afterSequence + 1,
        commands: [{ sequence: afterSequence + 1, type: 'CANCEL', data: {} }],
      };
    },
  }, 50);
  assert.equal(await cancelled.decide(request, new AbortController().signal), 'cancel');

  const malformed = new RuntimeApprovalBroker({
    async commands(afterSequence) {
      return {
        cursor: afterSequence + 1,
        commands: [{
          sequence: afterSequence + 1,
          type: 'APPROVAL_DECISION',
          data: { approvalRequestId: 'approval-1', decision: 'always' },
        }],
      };
    },
  }, 50);
  assert.equal(await malformed.decide(request, new AbortController().signal), 'cancel');

  const abortedController = new AbortController();
  abortedController.abort();
  const neverCalled = new RuntimeApprovalBroker({
    async commands() {
      assert.fail('aborted approval must not poll Runtime');
    },
  }, 50);
  assert.equal(await neverCalled.decide(request, abortedController.signal), 'cancel');
});
