import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { initConfig, loadConfig } from '../src/config.js';

test('init creates unpredictable credentials without overwriting existing configuration', () => {
  const dir = mkdtempSync(join(tmpdir(), 'phone-config-test-'));
  try {
    const one = join(dir, 'one.json'); const two = join(dir, 'two.json');
    initConfig(one); initConfig(two);
    assert.notEqual(loadConfig(one).harnessToken, loadConfig(two).harnessToken);
    assert.throws(() => initConfig(one), /exist/);
    assert.equal(loadConfig(one).stateFile, join(dir, 'bridge-state.json'));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('remote plaintext and unprotected public binding require explicit development opt-in', () => {
  const dir = mkdtempSync(join(tmpdir(), 'phone-config-test-'));
  try {
    const path = join(dir, 'config.json'); initConfig(path);
    const config = loadConfig(path);
    writeFileSync(path, JSON.stringify({ ...config, publicUrl: 'http://192.168.1.2:8787' }));
    assert.throws(() => loadConfig(path), /HTTPS/);
    writeFileSync(path, JSON.stringify({ ...config, host: '0.0.0.0', publicUrl: 'https://bridge.example.com' }));
    assert.throws(() => loadConfig(path), /loopback/);
    writeFileSync(path, JSON.stringify({ ...config, host: '0.0.0.0', publicUrl: 'http://192.168.1.2:8787', allowInsecureLan: true }));
    assert.equal(loadConfig(path).allowInsecureLan, true);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
