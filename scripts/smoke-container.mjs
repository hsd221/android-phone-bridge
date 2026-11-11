import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
import { request as httpRequest } from 'node:http';

if (process.argv.length > 3) throw new Error('Usage: node scripts/smoke-container.mjs [image]');
const image = process.argv[2] ?? 'phone-bridge:local';
const id = randomUUID().slice(0, 8);
const container = `phone-bridge-smoke-${id}`;
const volume = `${container}-data`;
const execFile = promisify(execFileCallback);
const docker = async (...args) => (await execFile('docker', args, { timeout: 120000, maxBuffer: 1024 * 1024 })).stdout.trim();
const cli = (...args) => docker('exec', container, 'node', '/app/dist/cli.js', ...args, '--config', '/data/bridge.config.json');
const configHash = () => docker('exec', container, 'node', '-e', "const fs=require('node:fs'),crypto=require('node:crypto');console.log(crypto.createHash('sha256').update(fs.readFileSync('/data/bridge.config.json')).digest('hex'))");
let base;
const headers = { host: '127.0.0.1:8787', 'content-type': 'application/json' };
// Preserve the container listener Host while connecting through a random host-side port.
// fetch intentionally ignores a custom Host header; node:http supports this proxy-style check.
const request = (path, payload) => new Promise((resolve, reject) => {
  const outgoing = httpRequest(`${base}${path}`, { method: payload ? 'POST' : 'GET', headers }, response => {
    const chunks = [];
    response.on('data', chunk => chunks.push(chunk));
    response.on('end', () => {
      try { resolve({ status: response.statusCode, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }); }
      catch (error) { reject(error); }
    });
  });
  outgoing.on('error', reject);
  outgoing.setTimeout(3000, () => outgoing.destroy(new Error('Container request timed out')));
  outgoing.end(payload ? JSON.stringify(payload) : undefined);
});
const start = async () => {
  await docker('run', '-d', '--name', container, '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
    '-p', '127.0.0.1::8787', '-v', `${volume}:/data`, '-e', 'PHONE_BRIDGE_PUBLIC_URL=https://phone-bridge-smoke.invalid', image);
  const port = await docker('port', container, '8787/tcp');
  assert.match(port, /^127\.0\.0\.1:\d+$/);
  base = `http://${port}`;
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const state = JSON.parse(await docker('inspect', '--format', '{{json .State}}', container));
    if (!state.Running) throw new Error(`Container stopped unexpectedly (${state.ExitCode}): ${await docker('logs', container)}`);
    try { if ((await request('/health')).status === 200) return; } catch {}
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error(`Container did not become ready: ${await docker('logs', container)}`);
};

try {
  await docker('volume', 'create', volume);
  await start();
  assert.notEqual(await docker('exec', container, 'node', '-e', 'console.log(process.getuid())'), '0');
  await docker('exec', container, 'node', '/app/dist/healthcheck.js');
  const initialHash = await configHash();
  const pair = JSON.parse(await cli('pair', '--json'));
  assert.equal(pair.url, 'https://phone-bridge-smoke.invalid');
  assert.match(pair.code, /^[0-9A-F]{16}$/);
  const deviceId = randomUUID();
  const pairDevice = () => request('/pair', { code: pair.code, deviceId, label: 'Container smoke phone' });
  const response = await pairDevice();
  assert.equal(response.status, 200);
  const identity = response.body;
  assert.equal((await pairDevice()).status, 403);
  assert.equal(JSON.parse(await cli('status')).device.deviceId, deviceId);
  const state = await docker('exec', container, 'node', '-e', "console.log(require('node:fs').readFileSync('/data/bridge-state.json','utf8'))");
  assert(!state.includes(identity.token));
  assert.equal(await docker('exec', container, 'node', '-e', "console.log(['/app/bridge.config.json','/app/bridge-state.json'].some(p=>require('node:fs').existsSync(p)))"), 'false');

  await docker('stop', '-t', '10', container);
  await docker('rm', container);
  await start();
  assert.equal(await configHash(), initialHash, 'container recreation must retain Harness configuration');
  assert.equal(JSON.parse(await cli('status')).device.deviceId, deviceId, 'device identity must survive container recreation');
  await cli('revoke');
  assert.equal(JSON.parse(await cli('status')).device, null);
  console.log(JSON.stringify({ image, ok: true, checks: ['non-root', 'read-only-rootfs', 'local-published-port', 'healthcheck', 'qr-pairing', 'one-time-code', 'hashed-device-token', 'configuration-and-identity-survive-recreate', 'revoke'] }, null, 2));
} finally {
  try { await docker('rm', '-f', container); } catch {}
  await docker('volume', 'rm', volume);
}
