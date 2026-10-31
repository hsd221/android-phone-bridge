import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { createBridge } from '../bridge/src/server.js';
import type { Config } from '../bridge/src/config.js';

const execFile = promisify(execFileCallback);
const serial = process.argv[2];
const enhanced = process.argv[3] === '--shizuku';
if (process.argv[3] && !enhanced) throw new Error('Unknown smoke option');
if (!serial || !/^emulator-\d+$/.test(serial)) throw new Error('Explicit emulator serial required: npx tsx scripts/android-smoke.ts emulator-5554. This test changes app permissions and accessibility settings.');
const adb = async (...args: string[]) => (await execFile('adb', ['-s', serial, ...args], { timeout: 240000, maxBuffer: 1024 * 1024 })).stdout;
const dir = await mkdtemp(join(tmpdir(), 'phone-bridge-device-'));
const token = randomBytes(32).toString('base64url');
const config: Config = { host: '127.0.0.1', port: 0, publicUrl: 'http://10.0.2.2:8787', harnessToken: token,
  stateFile: join(dir, 'state.json'), requestTimeoutMs: 30000, allowInsecureLan: true };
const bridge = createBridge(config);
const previousAccessibility = (await adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).trim();
const previousEnabled = (await adb('shell', 'settings', 'get', 'secure', 'accessibility_enabled')).trim();
const previousWifi = (await adb('shell', 'settings', 'get', 'global', 'wifi_on')).trim();
const previousData = (await adb('shell', 'settings', 'get', 'global', 'mobile_data')).trim();
try {
  const address = await bridge.listen();
  if (!address || typeof address === 'string') throw new Error('No listening address');
  // The actual Host includes the ephemeral port; update the shared config before calls.
  config.publicUrl = `http://10.0.2.2:${address.port}`;
  const component = 'io.github.hsd221.phonebridge/.PhoneAccessibilityService';
  await adb('install', '-r', resolve('android/app/build/outputs/apk/debug/app-debug.apk'));
  await adb('install', '-r', resolve('android/demo/build/outputs/apk/debug/demo-debug.apk'));
  await adb('install', '-r', resolve('android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'));
  await adb('shell', 'pm', 'clear', 'io.github.hsd221.phonebridge');
  await adb('shell', 'pm', 'clear', 'io.github.hsd221.phonebridge.demo');
  const permissionUi = await adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'io.github.hsd221.phonebridge.PermissionManagementTest',
    '-e', 'permissionUiTest', 'true', 'io.github.hsd221.phonebridge.test/androidx.test.runner.AndroidJUnitRunner');
  process.stdout.write(permissionUi);
  if (!permissionUi.includes('OK (3 tests)') || permissionUi.includes('FAILURES')) throw new Error('Permission management tests failed');
  const frontendUi = await adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'io.github.hsd221.phonebridge.FrontendInteractionTest',
    '-e', 'permissionUiTest', 'true', 'io.github.hsd221.phonebridge.test/androidx.test.runner.AndroidJUnitRunner');
  process.stdout.write(frontendUi);
  if (!frontendUi.includes('OK (7 tests)') || frontendUi.includes('FAILURES')) throw new Error('Frontend interaction tests failed');
  const updateUi = await adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'io.github.hsd221.phonebridge.AppUpdateUiTest',
    '-e', 'permissionUiTest', 'true', 'io.github.hsd221.phonebridge.test/androidx.test.runner.AndroidJUnitRunner');
  process.stdout.write(updateUi);
  if (!updateUi.includes('OK (9 tests)') || updateUi.includes('FAILURES')) throw new Error('App update tests failed');
  // These tests change grants and notification channels; start the MCP smoke flow from clean app state.
  await adb('shell', 'pm', 'clear', 'io.github.hsd221.phonebridge');
  for (const permission of ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'ACCESS_BACKGROUND_LOCATION', 'POST_NOTIFICATIONS']) {
    await adb('shell', 'pm', 'grant', 'io.github.hsd221.phonebridge', `android.permission.${permission}`);
  }
  await adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', component);
  await adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1');
  await adb('emu', 'geo', 'fix', '-74.0060', '40.7128');
  const pair = bridge.identities.createPairing();
  const result = await adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'io.github.hsd221.phonebridge.DeviceSmokeTest',
    '-e', 'bridgeUrl', config.publicUrl, '-e', 'pairCode', pair.code, '-e', 'harnessToken', token,
    '-e', 'shizuku', String(enhanced),
    'io.github.hsd221.phonebridge.test/androidx.test.runner.AndroidJUnitRunner');
  process.stdout.write(result);
  if (!result.includes('OK (1 test)') || result.includes('FAILURES')) process.exitCode = 1;
} finally {
  try {
    await adb('shell', 'svc', 'wifi', previousWifi === '0' ? 'disable' : 'enable');
    await adb('shell', 'svc', 'data', previousData === '0' ? 'disable' : 'enable');
    if (previousAccessibility === 'null' || previousAccessibility === '') await adb('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services');
    else await adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', previousAccessibility);
    await adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', previousEnabled === 'null' ? '0' : previousEnabled);
  } finally {
    await bridge.close();
    await rm(dir, { recursive: true, force: true });
  }
}
