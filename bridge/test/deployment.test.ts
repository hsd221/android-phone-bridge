import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { initConfig, loadConfig, localBaseUrl } from '../src/config.js';

test('container proxy configuration keeps the CLI on the local listener and credentials across initialization attempts', () => {
  const dir = mkdtempSync(join(tmpdir(), 'phone-container-config-'));
  try {
    const path = join(dir, 'config.json');
    initConfig(path, { host: '0.0.0.0', port: 9876, publicUrl: 'https://bridge.example.com', behindProxy: true });
    const before = readFileSync(path, 'utf8');
    const config = loadConfig(path);
    assert.equal(config.publicUrl, 'https://bridge.example.com');
    assert.equal(localBaseUrl(config), 'http://127.0.0.1:9876');
    assert.equal(config.allowInsecureLan, false);
    assert.equal(config.stateFile, join(dir, 'bridge-state.json'));
    assert.throws(() => initConfig(path, { publicUrl: 'https://other.example.com' }));
    assert.equal(readFileSync(path, 'utf8'), before, 'restarting deployment must never rotate the Harness identity');
    assert.equal(localBaseUrl({ ...config, host: '::' }), 'http://[::1]:9876');
    assert.equal(localBaseUrl({ ...config, host: '192.0.2.1' }), 'http://192.0.2.1:9876');
    assert.equal(localBaseUrl({ ...config, tls: { certFile: 'cert.pem', keyFile: 'key.pem' } }), config.publicUrl);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('proxy trust is an explicit choice requiring an HTTPS public origin', () => {
  const dir = mkdtempSync(join(tmpdir(), 'phone-container-config-'));
  try {
    const path = join(dir, 'config.json');
    initConfig(path);
    const config = loadConfig(path);
    const write = (changes: Record<string, unknown>) => writeFileSync(path, JSON.stringify({ ...config, ...changes }));
    write({ host: '0.0.0.0', publicUrl: 'https://bridge.example.com' });
    assert.throws(() => loadConfig(path), /loopback|proxy|TLS/i);
    write({ host: '0.0.0.0', publicUrl: 'http://bridge.example.com', behindProxy: true, allowInsecureLan: true });
    assert.throws(() => loadConfig(path), /HTTPS/i, 'development opt-in cannot silently downgrade a trusted proxy deployment');
    write({ host: '0.0.0.0', publicUrl: 'https://bridge.example.com', behindProxy: true });
    assert.equal(loadConfig(path).behindProxy, true);
    for (const publicUrl of ['https://user:password@bridge.example.com', 'https://bridge.example.com/path', 'https://bridge.example.com/?token=secret', 'https://bridge.example.com/#fragment']) {
      write({ host: '0.0.0.0', publicUrl, behindProxy: true });
      assert.throws(() => loadConfig(path), /origin|path|query|credentials/i);
    }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
