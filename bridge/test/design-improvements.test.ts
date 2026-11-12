import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { createBridge } from '../src/server.js';
import type { Config } from '../src/config.js';
import { BRIDGE_VERSION, isOperation, toolInputs } from '../src/protocol.js';

test('release versions agree across the package, lockfile and bridge identity', async () => {
  const pkg = JSON.parse(await readFile(new URL('../../package.json', import.meta.url), 'utf8'));
  const lock = JSON.parse(await readFile(new URL('../../package-lock.json', import.meta.url), 'utf8'));
  assert.equal(pkg.version, BRIDGE_VERSION);
  assert.equal(lock.version, BRIDGE_VERSION);
  assert.equal(lock.packages[''].version, BRIDGE_VERSION);
});

test('key and long-press contracts reject unbounded or ambiguous actions', () => {
  for (const key of ['HOME', 'BACK', 'ENTER', 'TAB']) {
    assert(toolInputs.phone_key.safeParse({ key }).success);
    assert(toolInputs.phone_key.safeParse({ key, packageName: 'com.example.app' }).success);
  }
  for (const args of [{ key: 'POWER' }, { key: 'SLEEP' }, { key: 'BACK; input text unsafe' }, { key: 4 }, {},
    { key: 'BACK', allowSystemOverlay: true }, { key: 'BACK', packageName: '' }]) {
    assert(!toolInputs.phone_key.safeParse(args).success, JSON.stringify(args));
  }
  assert(isOperation('phone_key'));
  const point = { packageName: 'com.example.app', x: 100, y: 200 };
  const node = { packageName: 'com.example.app', nodeId: 'snapshot:0' };
  assert(!Object.hasOwn(toolInputs.phone_tap.parse(point), 'durationMs'), 'ordinary taps must remain compatible with older phones');
  for (const durationMs of [50, 499, 500, 1000, 2000]) {
    assert(toolInputs.phone_tap.safeParse({ ...point, durationMs }).success);
    assert(toolInputs.phone_tap.safeParse({ ...node, durationMs }).success);
  }
  for (const durationMs of [0, 49, 2001, 500.5, '500']) {
    assert(!toolInputs.phone_tap.safeParse({ ...point, durationMs }).success);
  }
  assert(!toolInputs.phone_tap.safeParse({ ...node, x: 1, y: 2 }).success);
});

test('lock is a separate empty operation rather than a power-key command', () => {
  assert.deepEqual(toolInputs.phone_lock.parse({}), {});
  assert(isOperation('phone_lock'));
  for (const args of [null, [], 'POWER', 0, { key: 'POWER' }, { wake: true }, { force: true },
    { packageName: 'com.example.app' }, { timeoutMs: 5000 }, { pin: 'synthetic-pin' }]) {
    assert(!toolInputs.phone_lock.safeParse(args).success, JSON.stringify(args));
  }
});

test('status discovers bridge version and tool support with an offline or online phone', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'phone-design-status-'));
  const config: Config = { host: '127.0.0.1', port: 0, publicUrl: 'http://127.0.0.1:8787',
    harnessToken: 'design-fixture-harness-at-least-32-characters', stateFile: join(dir, 'state.json'), requestTimeoutMs: 1000, allowInsecureLan: false };
  const bridge = createBridge(config);
  let ws: WebSocket | undefined;
  try {
    const address = await bridge.listen();
    assert(address && typeof address !== 'string');
    const base = `http://127.0.0.1:${address.port}`;
    const headers = { authorization: `Bearer ${config.harnessToken}`, 'content-type': 'application/json' };
    const offline = await bridge.broker.call('phone_status', {});
    assert(offline.ok);
    const data = offline.data as any;
    assert.equal(data.connected, false);
    assert.equal(data.bridge.version, '0.3.0');
    assert.equal(data.bridge.protocol, 1);
    assert.deepEqual([...data.bridge.supportedTools].sort(), Object.keys(toolInputs).sort());
    assert(!data.bridge.supportedTools.some((name: string) => /shell/i.test(name)));
    const admin = await fetch(`${base}/admin/status`, { headers }).then(r => r.json()) as any;
    assert.deepEqual(admin.bridge, data.bridge);
    assert.equal((await fetch(`${base}/admin/status`)).status, 401);
    const deviceId = randomUUID();
    const pair = bridge.identities.createPairing();
    const identity = bridge.identities.pair(pair.code, deviceId, 'Synthetic design fixture');
    assert(identity);
    ws = new WebSocket(`${base.replace('http:', 'ws:')}/device`, { headers: { authorization: `Bearer ${identity.token}` } });
    await once(ws, 'open');
    ws.send(JSON.stringify({ type: 'hello', protocol: 1, deviceId }));
    ws.ping(); await once(ws, 'pong');
    let forwardedTap: unknown;
    ws.on('message', raw => {
      const request = JSON.parse(raw.toString());
      if (request.type === 'request' && request.tool === 'phone_tap') forwardedTap = request.args;
      if (request.type === 'request') ws!.send(JSON.stringify({ type: 'result', id: request.id, deviceId,
        result: { ok: true, data: { connected: true, permissions: { accessibility: true } } } }));
    });
    const online = await bridge.broker.call('phone_status', {});
    assert(online.ok);
    assert.equal((online.data as any).connected, true);
    assert.equal((online.data as any).permissions.accessibility, true);
    assert.deepEqual((online.data as any).bridge, data.bridge);
    const legacyTap = { packageName: 'com.example.app', x: 12, y: 34 };
    await bridge.broker.call('phone_tap', legacyTap);
    assert.deepEqual(forwardedTap, legacyTap, 'unspecified durations must not be added to the wire for legacy phones');
  } finally { ws?.terminate(); await bridge.close(); await rm(dir, { recursive: true, force: true }); }
});

test('stdio preserves useful legacy-bridge errors and marks uncertain operations', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'phone-design-errors-'));
  let status = 400;
  let body = JSON.stringify({ error: 'Unknown tool' });
  const server = createServer((req, res) => {
    req.resume();
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(body);
  });
  let client: Client | undefined;
  try {
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    const address = server.address(); assert(address && typeof address !== 'string');
    const configPath = join(dir, 'config.json');
    await writeFile(configPath, JSON.stringify({ host: '127.0.0.1', port: address.port,
      publicUrl: `http://127.0.0.1:${address.port}`, harnessToken: 'design-fixture-harness-at-least-32-characters',
      stateFile: join(dir, 'state.json'), requestTimeoutMs: 1000, allowInsecureLan: false }), { mode: 0o600 });
    client = new Client({ name: 'design-error-test', version: '1' });
    await client.connect(new StdioClientTransport({ command: process.execPath,
      args: ['--import', 'tsx', 'bridge/src/cli.ts', 'mcp', '--config', configPath], stderr: 'pipe' }));
    assert.equal(client.getServerVersion()?.version, BRIDGE_VERSION);
    const call = async (tool = 'phone_device_info') => {
      const result = await client!.callTool({ name: tool, arguments: tool === 'phone_key' ? { key: 'BACK' } : {} });
      assert.equal(result.isError, true);
      return JSON.parse((result.content as { type: string; text: string }[])[0]!.text);
    };
    const unsupported = await call();
    assert.equal(unsupported.error.code, 'TOOL_NOT_SUPPORTED_BY_BRIDGE');
    assert(!unsupported.error.outcomeUnknown);
    body = JSON.stringify({ error: 'Invalid request' });
    assert.equal((await call()).error.code, 'INVALID_ARGUMENT');
    for (const [http, code] of [[401, 'BRIDGE_AUTH_FAILED'], [403, 'BRIDGE_ACCESS_DENIED'], [404, 'BRIDGE_ENDPOINT_NOT_FOUND']] as const) {
      status = http;
      assert.equal((await call()).error.code, code);
    }
    status = 503; body = JSON.stringify({ error: 'Unavailable' });
    const unavailable = await call('phone_key');
    assert.equal(unavailable.error.code, 'BRIDGE_UNAVAILABLE');
    assert.equal(unavailable.error.outcomeUnknown, true);
    status = 200; body = 'not valid JSON';
    assert.equal((await call()).error.code, 'BRIDGE_PROTOCOL_ERROR');
    const uncertain = await call('phone_key');
    assert.equal(uncertain.error.code, 'BRIDGE_PROTOCOL_ERROR');
    assert.equal(uncertain.error.outcomeUnknown, true);
    body = JSON.stringify({ ok: false, error: { code: 'APP_DENIED', message: 'Synthetic refusal' } });
    const refused = await call('phone_key');
    assert.equal(refused.error.code, 'APP_DENIED');
    assert(!refused.error.outcomeUnknown);
  } finally {
    await client?.close(); server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
    await rm(dir, { recursive: true, force: true });
  }
});
