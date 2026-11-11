import assert from 'node:assert/strict';
import { execFile as execFileCallback, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, copyFile, chmod, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { createServer } from 'node:net';
import { randomUUID } from 'node:crypto';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

// The harness uses Node; the application under test runs without node_modules or Node on PATH.
if (process.argv.length > 3) throw new Error('Usage: node scripts/smoke-binary.mjs [binary]');
const version = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8')).version;
const defaultName = `phone-bridge-${version}-${process.platform === 'win32' ? 'windows' : 'linux'}-${process.arch}${process.platform === 'win32' ? '.exe' : ''}`;
const input = process.argv[2] ?? resolve(import.meta.dirname, '..', 'artifacts', defaultName);
const execFile = promisify(execFileCallback);
const directory = await mkdtemp(join(tmpdir(), 'phone-binary-smoke-'));
const binary = join(directory, process.platform === 'win32' ? 'phone-bridge.exe' : 'phone-bridge');
const configPath = join(directory, 'bridge.config.json');
const env = { ...process.env, PATH: directory, NODE_PATH: '', NODE_OPTIONS: '' };
let service;
let logs = '';

const run = async (...args) => (await execFile(binary, args, { cwd: directory, env, timeout: 20000, maxBuffer: 1024 * 1024 })).stdout;
const freePort = async () => {
  const listener = createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  const address = listener.address();
  assert(address && typeof address !== 'string');
  await new Promise((resolve, reject) => listener.close(error => error ? reject(error) : resolve()));
  return address.port;
};
const stop = async () => {
  if (!service || service.exitCode !== null) return;
  const processToStop = service;
  await new Promise((resolve, reject) => {
    const deadline = setTimeout(() => { processToStop.kill('SIGKILL'); reject(new Error('Packaged Bridge did not stop')); }, 10000);
    processToStop.once('exit', () => { clearTimeout(deadline); resolve(); });
    processToStop.kill('SIGTERM');
  });
};
const start = async base => {
  service = spawn(binary, ['serve', '--config', configPath], { cwd: directory, env, stdio: ['ignore', 'pipe', 'pipe'] });
  service.stdout.on('data', chunk => { logs += chunk; });
  service.stderr.on('data', chunk => { logs += chunk; });
  service.on('error', error => { logs += error.message; });
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (service.exitCode !== null) throw new Error(`Packaged Bridge exited: ${logs}`);
    try { if ((await fetch(`${base}/health`, { signal: AbortSignal.timeout(1000) })).ok) return; } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(`Packaged Bridge was not ready: ${logs}`);
};

try {
  await copyFile(resolve(input), binary);
  await chmod(binary, 0o755);
  assert((await run('--help')).includes('phone-bridge'));
  await run('init', '--config', configPath);
  const initial = await readFile(configPath, 'utf8');
  await assert.rejects(run('init', '--config', configPath));
  assert.equal(await readFile(configPath, 'utf8'), initial);
  const config = JSON.parse(initial);
  const port = await freePort();
  const base = `http://127.0.0.1:${port}`;
  await writeFile(configPath, JSON.stringify({ ...config, port, publicUrl: base }));
  await start(base);
  const health = await (await fetch(`${base}/health`)).json();
  assert.equal(health.ok, true);
  const first = JSON.parse(await run('pair', '--config', configPath, '--json'));
  const next = JSON.parse(await run('pair', '--config', configPath, '--json'));
  assert.equal(next.url, base);
  assert.match(next.code, /^[0-9A-F]{16}$/);
  const deviceId = randomUUID();
  const pair = code => fetch(`${base}/pair`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code, deviceId, label: 'Binary smoke phone' }) });
  assert.equal((await pair(first.code)).status, 403);
  const paired = await pair(next.code);
  assert.equal(paired.status, 200);
  const identity = await paired.json();
  assert.equal(identity.deviceId, deviceId);
  assert.equal((await pair(next.code)).status, 403);
  const state = await readFile(join(directory, 'bridge-state.json'), 'utf8');
  assert(!state.includes(identity.token), 'packaging may not change credential hashing');
  await stop();
  await start(base);
  const status = JSON.parse(await run('status', '--config', configPath));
  assert.equal(status.device.deviceId, deviceId, 'device identity must survive a binary restart');

  const client = new Client({ name: 'binary-smoke', version: '1' });
  try {
    await client.connect(new StdioClientTransport({ command: binary, args: ['mcp', '--config', configPath], cwd: directory, env }));
    assert((await client.listTools()).tools.some(tool => tool.name === 'phone_status'));
    const result = await client.callTool({ name: 'phone_status', arguments: {} });
    assert.equal(result.isError, false);
    assert.equal(JSON.parse(result.content.find(item => item.type === 'text').text).data.connected, false);
  } finally { await client.close(); }
  await run('revoke', '--config', configPath);
  assert.equal(JSON.parse(await run('status', '--config', configPath)).device, null);
  console.log(JSON.stringify({ binary: basename(input), platform: process.platform, arch: process.arch, checks: ['standalone-without-node-on-path', 'init-preserves-identity', 'health', 'pair-renewal-and-replay', 'hashed-persistence', 'restart', 'stdio-mcp', 'revoke'], ok: true }, null, 2));
} finally {
  try { await stop(); } finally { await rm(directory, { recursive: true, force: true }); }
}
