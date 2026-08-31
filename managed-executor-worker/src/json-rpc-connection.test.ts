import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { PassThrough } from 'node:stream';
import test from 'node:test';
import { JsonRpcConnection } from './json-rpc-connection.js';
import { JsonRpcRemoteError } from './protocol.js';

test('correlates out-of-order app-server responses without exposing transport details', async () => {
  const serverToClient = new PassThrough();
  const clientToServer = new PassThrough();
  const lines = createInterface({ input: clientToServer, crlfDelay: Infinity });
  const connection = new JsonRpcConnection(serverToClient, clientToServer, { requestTimeoutMs: 2_000 });

  const firstLineReady = once(lines, 'line');
  const first = connection.request<{ value: string }>('first', { safe: true });
  const [firstLine] = await firstLineReady as [string];
  const firstRequest = JSON.parse(firstLine) as { id: number };
  const secondLineReady = once(lines, 'line');
  const second = connection.request<{ value: string }>('second');
  const [secondLine] = await secondLineReady as [string];
  const secondRequest = JSON.parse(secondLine) as { id: number };

  serverToClient.write(`${JSON.stringify({ id: secondRequest.id, result: { value: 'two' } })}\n`);
  serverToClient.write(`${JSON.stringify({ id: firstRequest.id, result: { value: 'one' } })}\n`);

  assert.deepEqual(await first, { value: 'one' });
  assert.deepEqual(await second, { value: 'two' });
  connection.close();
  lines.close();
});

test('separates server requests from notifications and writes a decision response', async () => {
  const serverToClient = new PassThrough();
  const clientToServer = new PassThrough();
  const lines = createInterface({ input: clientToServer, crlfDelay: Infinity });
  const connection = new JsonRpcConnection(serverToClient, clientToServer);
  const requests: unknown[] = [];
  const notifications: unknown[] = [];
  connection.onServerRequest((value) => requests.push(value));
  connection.onNotification((value) => notifications.push(value));

  serverToClient.write(`${JSON.stringify({
    id: 'approval-1',
    method: 'item/commandExecution/requestApproval',
    params: { itemId: 'item-1' },
  })}\n`);
  serverToClient.write(`${JSON.stringify({
    method: 'turn/started',
    params: { turn: { id: 'turn-1' } },
  })}\n`);
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(requests.length, 1);
  assert.equal(notifications.length, 1);
  const responseLineReady = once(lines, 'line');
  connection.respond('approval-1', { decision: 'cancel' });
  const [responseLine] = await responseLineReady as [string];
  assert.deepEqual(JSON.parse(responseLine), {
    id: 'approval-1',
    result: { decision: 'cancel' },
  });
  connection.close();
  lines.close();
});

test('returns a typed remote error and never includes raw invalid JSON in protocol errors', async () => {
  const serverToClient = new PassThrough();
  const clientToServer = new PassThrough();
  const lines = createInterface({ input: clientToServer, crlfDelay: Infinity });
  const connection = new JsonRpcConnection(serverToClient, clientToServer);
  const requestLineReady = once(lines, 'line');
  const pending = connection.request('thread/start');
  const [requestLine] = await requestLineReady as [string];
  const request = JSON.parse(requestLine) as { id: number };
  serverToClient.write(`${JSON.stringify({
    id: request.id,
    error: { code: -32000, message: 'not available' },
  })}\n`);
  await assert.rejects(pending, (error: unknown) => {
    assert.ok(error instanceof JsonRpcRemoteError);
    assert.equal(error.code, -32000);
    return true;
  });

  const protocolErrors: Error[] = [];
  connection.onProtocolError((error) => protocolErrors.push(error));
  serverToClient.write('{"authorization":"Bearer should-never-appear"\n');
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(protocolErrors.length, 1);
  assert.equal(protocolErrors[0]?.message, 'Codex app-server emitted invalid JSON');
  assert.doesNotMatch(protocolErrors[0]?.message || '', /Bearer|authorization/);
  connection.close();
  lines.close();
});
