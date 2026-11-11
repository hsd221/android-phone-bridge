import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { initConfig, loadConfig } from '../src/config.js';
import { createBridge } from '../src/server.js';
import { createPairingPayload, renderPairingQr } from '../src/pairing.js';

const execFile = promisify(execFileCallback);
const cli = (...args: string[]) => execFile(process.execPath, ['--import', 'tsx', 'bridge/src/cli.ts', ...args], { timeout: 15000, maxBuffer: 1024 * 1024 });

test('QR contract contains only the temporary device pairing fields', () => {
  const pairing = { code: '0123456789ABCDEF', expiresAt: Date.now() + 300000 };
  const payload = createPairingPayload('https://bridge.example.com', pairing);
  // A single, language-neutral JSON contract is parsed by the Android client.
  const decoded = typeof payload === 'string' ? JSON.parse(payload) : payload;
  assert.deepEqual(decoded, { type: 'phone-bridge-pair', version: 1, url: 'https://bridge.example.com', ...pairing });
  const raw = JSON.stringify(decoded);
  assert(!/harness|token|insecure/i.test(raw));
  const qr = renderPairingQr(raw);
  assert(qr.includes('\u001b['), 'explicit black/white ANSI colors keep the QR readable in both terminal themes');
  assert(qr.split('\n').length > 20, 'rendering must contain the entire QR, including its quiet zone');
});

test('CLI pair JSON works without reaching the public proxy and older QR codes cannot be used after renewal', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'phone-pair-cli-'));
  const path = join(dir, 'config.json');
  initConfig(path, { host: '0.0.0.0', port: 0, publicUrl: 'https://unreachable.invalid', behindProxy: true });
  const config = loadConfig(path);
  const bridge = createBridge(config);
  try {
    const address = await bridge.listen();
    assert(address && typeof address !== 'string');
    config.port = address.port;
    await writeFile(path, JSON.stringify(config));
    const first = await cli('pair', '--config', path, '--json');
    const one = JSON.parse(first.stdout);
    assert.equal(one.url, config.publicUrl);
    assert.match(one.code, /^[0-9A-F]{16}$/);
    assert(one.expiresAt > Date.now());
    assert(!first.stdout.includes('\u001b'), 'machine JSON output may not contain ANSI sequences');

    const text = await cli('pair', '--config', path);
    assert(!text.stdout.includes('\u001b'), 'redirected default output should not write ANSI QR art');
    assert(text.stdout.includes(config.publicUrl));
    const deviceId = randomUUID();
    assert.equal(bridge.identities.pair(one.code, deviceId, 'Old QR'), null);

    const explicit = await cli('pair', '--config', path, '--qr');
    assert(explicit.stdout.includes('\u001b['), 'users may explicitly request QR output through a pipe');
    const current = await cli('pair', '--config', path, '--json');
    const issued = JSON.parse(current.stdout);
    assert(bridge.identities.pair(issued.code, deviceId, 'Scanned phone'));
    assert.equal(bridge.identities.pair(issued.code, deviceId, 'Replay QR'), null);
    const persisted = await readFile(config.stateFile, 'utf8');
    assert(!persisted.includes(issued.code));
    await assert.rejects(cli('pair', '--config', path, '--json'), /409/);
    assert((await cli('status', '--config', path)).stdout.includes('Scanned phone'));
  } finally { await bridge.close(); await rm(dir, { recursive: true, force: true }); }
});
