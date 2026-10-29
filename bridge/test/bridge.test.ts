import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { get } from 'node:http';
import WebSocket from 'ws';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { createBridge } from '../src/server.js';
import type { Config } from '../src/config.js';
import { IdentityStore } from '../src/identity.js';

async function fixture(timeout = 1000) {
  const dir = await mkdtemp(join(tmpdir(), 'phone-bridge-test-'));
  const config: Config = { host: '127.0.0.1', port: 0, publicUrl: 'http://127.0.0.1:8787',
    harnessToken: 'harness-test-identity-at-least-32-chars', stateFile: join(dir, 'state.json'), requestTimeoutMs: timeout, allowInsecureLan: false };
  const bridge = createBridge(config);
  const address = await bridge.listen();
  assert(address && typeof address !== 'string');
  const base = `http://127.0.0.1:${address.port}`;
  const auth = { authorization: `Bearer ${config.harnessToken}`, 'content-type': 'application/json' };
  const deviceId = randomUUID();
  const pair = async () => {
    const issued = await fetch(`${base}/admin/pair`, { method: 'POST', headers: auth });
    const { code } = await issued.json() as { code: string };
    const response = await fetch(`${base}/pair`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code, deviceId, label: 'Test phone' }) });
    assert.equal(response.status, 200);
    const identity = await response.json() as { token: string };
    return { ...identity, code };
  };
  const connect = async (token: string) => {
    const ws = new WebSocket(`${base.replace('http:', 'ws:')}/device`, { headers: { authorization: `Bearer ${token}` } });
    await once(ws, 'open');
    ws.send(JSON.stringify({ type: 'hello', protocol: 1, deviceId }));
    // The server has processed hello once a ping has made a round trip.
    ws.ping(); await once(ws, 'pong');
    return ws;
  };
  const call = (tool: string, args: unknown = {}) => fetch(`${base}/api/call`, { method: 'POST', headers: auth, body: JSON.stringify({ tool, args }) }).then(r => r.json()) as Promise<any>;
  return { bridge, base, auth, pair, connect, call, config, deviceId, async close() { await bridge.close(); await rm(dir, { recursive: true, force: true }); } };
}

test('one-time pairing survives restart, separates identities, and revokes an active phone', async () => {
  const f = await fixture();
  try {
    assert.equal((await fetch(`${f.base}/admin/pair`, { method: 'POST' })).status, 401);
    const identity = await f.pair();
    const persisted = await readFile(f.config.stateFile, 'utf8');
    assert(!persisted.includes(identity.token), 'raw device token must not be stored');
    const store = new IdentityStore(f.config.stateFile);
    assert(store.authenticate(identity.token));
    assert(!store.authenticate(f.config.harnessToken));
    assert.equal((await fetch(`${f.base}/pair`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code: identity.code, deviceId: f.deviceId, label: 'replay' }) })).status, 403);
    assert.equal((await fetch(`${f.base}/admin/status`, { headers: { authorization: `Bearer ${identity.token}` } })).status, 401);
    const ws = await f.connect(identity.token);
    const closed = once(ws, 'close');
    assert.equal((await fetch(`${f.base}/device/revoke`, { method: 'DELETE', headers: { authorization: `Bearer ${identity.token}` } })).status, 200);
    await closed;
    assert(!new IdentityStore(f.config.stateFile).authenticate(identity.token));
    assert.equal((await f.call('phone_status')).error.code, 'PHONE_OFFLINE');
  } finally { await f.close(); }
});

test('expired pairing codes and hostile browser origins are refused', async () => {
  const f = await fixture();
  try {
    const code = f.bridge.identities.createPairing(-1).code;
    assert.equal(f.bridge.identities.pair(code, f.deviceId, 'test'), null);
    assert.equal((await fetch(`${f.base}/admin/status`, { headers: { ...f.auth, origin: 'https://untrusted.example' } })).status, 403);
    const hostileHostStatus = await new Promise<number | undefined>((resolve, reject) => {
      get(`${f.base}/health`, { headers: { host: 'attacker.example' } }, res => { res.resume(); resolve(res.statusCode); }).on('error', reject);
    });
    assert.equal(hostileHostStatus, 403);
  } finally { await f.close(); }
});

test('real MCP client lists and calls phone tools, including image content', async () => {
  const f = await fixture();
  const client = new Client({ name: 'test-harness', version: '1' });
  try {
    const identity = await f.pair();
    const ws = await f.connect(identity.token);
    ws.on('message', raw => {
      const req = JSON.parse(raw.toString());
      if (req.type !== 'request') return;
      assert.equal(req.deviceId, f.deviceId);
      assert(req.expiresAt > req.issuedAt);
      const result = req.tool === 'phone_screenshot' ? { ok: true, data: { base64: 'iVBORw0KGgo=', mimeType: 'image/png' } } : { ok: true, data: { permissions: { location: false } } };
      ws.send(JSON.stringify({ type: 'result', id: req.id, deviceId: f.deviceId, result }));
    });
    await client.connect(new StreamableHTTPClientTransport(new URL(`${f.base}/mcp`), { requestInit: { headers: f.auth } }));
    const tools = await client.listTools();
    assert.equal(tools.tools.length, 10);
    assert(!tools.tools.some(t => /shell/i.test(t.name)));
    const unlock = tools.tools.find(t => t.name === 'phone_unlock');
    assert(unlock);
    assert.deepEqual(unlock.inputSchema.properties, {});
    assert.equal(unlock.inputSchema.additionalProperties, false);
    assert.equal(unlock.annotations?.readOnlyHint, false);
    assert.equal(unlock.annotations?.destructiveHint, true);
    assert.equal(unlock.annotations?.idempotentHint, false);
    const status = await client.callTool({ name: 'phone_status', arguments: {} });
    assert.equal(status.isError, false);
    const image = await client.callTool({ name: 'phone_screenshot', arguments: {} });
    assert.equal((image.content as any[])[0].type, 'image');
    assert(!JSON.parse((image.content as any[])[1].text).data.base64, 'metadata must not duplicate image bytes');
    const invalid = await client.callTool({ name: 'phone_tap', arguments: { packageName: 'com.example.app', x: 12 } });
    assert.equal(invalid.isError, true);
  } finally { await client.close(); await f.close(); }
});

test('disconnect fails in-flight actions and reconnect never replays them', async () => {
  const f = await fixture();
  try {
    const identity = await f.pair();
    const ws = await f.connect(identity.token);
    const request = once(ws, 'message');
    const pending = f.call('phone_input', { packageName: 'com.example.app', text: 'do not repeat' });
    await request;
    ws.terminate();
    const result = await pending;
    assert.equal(result.error.code, 'PHONE_OFFLINE');
    assert.equal(result.error.outcomeUnknown, true);
    const next = await f.connect(identity.token);
    let messages = 0;
    next.on('message', () => messages++);
    next.ping(); await once(next, 'pong');
    assert.equal(messages, 0);
  } finally { await f.close(); }
});

test('unlock rejects credential parameters and forwards only an empty command', async () => {
  const f = await fixture();
  try {
    const identity = await f.pair();
    const ws = await f.connect(identity.token);
    const incoming: any[] = [];
    ws.on('message', raw => incoming.push(JSON.parse(raw.toString())));
    for (const args of [{ pin: 'synthetic-pin' }, { password: 'synthetic-password' }, { pattern: 'synthetic-pattern' }, { text: 'synthetic-text' }]) {
      assert.equal((await f.call('phone_unlock', args)).error.code, 'INVALID_ARGUMENT');
    }
    ws.ping(); await once(ws, 'pong');
    assert.equal(incoming.length, 0, 'invalid credential-bearing commands must not reach the phone');
    const request = once(ws, 'message');
    const pending = f.call('phone_unlock');
    const [raw] = await request;
    const message = JSON.parse(raw.toString());
    assert.equal(message.tool, 'phone_unlock');
    assert.deepEqual(message.args, {});
    ws.send(JSON.stringify({ type: 'result', id: message.id, deviceId: f.deviceId, result: { ok: true, data: { unlocked: true, path: 'local_credential' } } }));
    assert.deepEqual((await pending).data, { unlocked: true, path: 'local_credential' });
  } finally { await f.close(); }
});

test('unlock disconnect and cancellation are uncertain operations and never replay', async () => {
  const f = await fixture();
  try {
    const identity = await f.pair();
    const ws = await f.connect(identity.token);
    const request = once(ws, 'message');
    const pending = f.call('phone_unlock');
    await request;
    ws.terminate();
    const result = await pending;
    assert.equal(result.error.code, 'PHONE_OFFLINE');
    assert.equal(result.error.outcomeUnknown, true);
    const next = await f.connect(identity.token);
    const incoming: any[] = [];
    next.on('message', raw => incoming.push(JSON.parse(raw.toString())));
    next.ping(); await once(next, 'pong');
    assert.equal(incoming.length, 0);

    const controller = new AbortController();
    const dispatched = once(next, 'message');
    const cancelled = f.bridge.broker.call('phone_unlock', {}, controller.signal);
    const [raw] = await dispatched;
    controller.abort();
    const cancellation = await cancelled;
    assert.equal(cancellation.ok, false);
    if (cancellation.ok) throw new Error('Cancelled unlock succeeded');
    assert.equal(cancellation.error.code, 'CANCELLED');
    assert.equal(cancellation.error.outcomeUnknown, true);
    next.ping(); await once(next, 'pong');
    assert.equal(incoming[1].type, 'cancel');
    assert.equal(incoming[1].id, JSON.parse(raw.toString()).id);
  } finally { await f.close(); }
});

test('deadline sends cancellation and late results cannot resolve another request', async () => {
  const f = await fixture(80);
  try {
    const identity = await f.pair();
    const ws = await f.connect(identity.token);
    const incoming: any[] = [];
    ws.on('message', raw => incoming.push(JSON.parse(raw.toString())));
    const result = await f.call('phone_tap', { packageName: 'com.example.app', x: 1, y: 2 });
    assert.equal(result.error.code, 'TIMEOUT');
    assert.equal(result.error.outcomeUnknown, true);
    ws.ping(); await once(ws, 'pong');
    assert.equal(incoming[1].type, 'cancel');
    assert.equal(incoming[1].id, incoming[0].id);
    ws.send(JSON.stringify({ type: 'result', id: incoming[0].id, deviceId: f.deviceId, result: { ok: true, data: 'late' } }));
    const next = f.call('phone_status');
    const [raw] = await once(ws, 'message');
    const request = JSON.parse(raw.toString());
    ws.send(JSON.stringify({ type: 'result', id: request.id, deviceId: f.deviceId, result: { ok: false, error: { code: 'PAUSED', message: 'User paused all access' } } }));
    assert.equal((await next).error.code, 'PAUSED');
  } finally { await f.close(); }
});

test('stdio CLI forwards MCP calls to the running authenticated bridge', async () => {
  const f = await fixture();
  const client = new Client({ name: 'stdio-harness', version: '1' });
  const configPath = join(f.config.stateFile, '..', 'config.json');
  try {
    await writeFile(configPath, JSON.stringify({ ...f.config, port: Number(new URL(f.base).port), publicUrl: f.base }));
    await client.connect(new StdioClientTransport({ command: process.execPath,
      args: ['--import', 'tsx', 'bridge/src/cli.ts', 'mcp', '--config', configPath] }));
    assert.equal((await client.listTools()).tools.length, 10);
    const result = await client.callTool({ name: 'phone_status', arguments: {} });
    assert.equal(result.isError, true);
    assert.equal(JSON.parse((result.content as any[])[0].text).error.code, 'PHONE_OFFLINE');
  } finally { await client.close(); await f.close(); }
});
