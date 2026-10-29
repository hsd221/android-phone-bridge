import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { createBridge } from '../bridge/src/server.js';
import type { Config } from '../bridge/src/config.js';

const serial = process.argv[2];
if (!serial || !/^emulator-\d+$/.test(serial)) throw new Error('Explicit dedicated emulator serial required; this test resets test app data and temporarily sets synthetic lock credentials.');
const execFile = promisify(execFileCallback);
const adb = async (...args: string[]) => (await execFile('adb', ['-s', serial, ...args], { timeout: 240000, maxBuffer: 1024 * 1024 })).stdout;
const dir = await mkdtemp(join(tmpdir(), 'phone-bridge-unlock-'));
const token = randomBytes(32).toString('base64url');
const config: Config = { host: '127.0.0.1', port: 0, publicUrl: 'http://10.0.2.2:8787', harnessToken: token,
  stateFile: join(dir, 'state.json'), requestTimeoutMs: 30000, allowInsecureLan: true };
const bridge = createBridge(config);
const previousAccessibility = (await adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).trim();
const previousEnabled = (await adb('shell', 'settings', 'get', 'secure', 'accessibility_enabled')).trim();
try {
  const address = await bridge.listen();
  assert(address && typeof address !== 'string');
  config.publicUrl = `http://10.0.2.2:${address.port}`;
  for (const apk of ['app/build/outputs/apk/debug/app-debug.apk', 'demo/build/outputs/apk/debug/demo-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) {
    await adb('install', '-r', resolve('android', apk));
  }
  await adb('shell', 'pm', 'clear', 'io.github.hsd221.phonebridge');
  await adb('shell', 'pm', 'grant', 'io.github.hsd221.phonebridge', 'android.permission.POST_NOTIFICATIONS');
  const pair = bridge.identities.createPairing();
  // Actual lock credentials are generated and used only inside instrumentation on the
  // dedicated emulator. They never enter Bridge configuration, tool arguments or output.
  const result = await adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'io.github.hsd221.phonebridge.UnlockTest',
    '-e', 'unlockTest', 'true', '-e', 'bridgeUrl', config.publicUrl, '-e', 'pairCode', pair.code,
    '-e', 'harnessToken', token, 'io.github.hsd221.phonebridge.test/androidx.test.runner.AndroidJUnitRunner');
  process.stdout.write(result);
  assert(result.includes('OK (1 test)') && !result.includes('FAILURES'), 'Local unlock instrumentation failed');
} finally {
  bridge.identities.revoke(); bridge.broker.disconnect();
  try {
    if (previousAccessibility === 'null' || previousAccessibility === '') await adb('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services');
    else await adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', previousAccessibility);
    await adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', previousEnabled === 'null' ? '0' : previousEnabled);
  } finally {
    await bridge.close();
    await rm(dir, { recursive: true, force: true });
  }
}
