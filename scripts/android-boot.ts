import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { createBridge } from '../bridge/src/server.js';
import type { Config } from '../bridge/src/config.js';

const serial = process.argv[2];
if (!serial || !/^emulator-\d+$/.test(serial)) throw new Error('Explicit dedicated emulator serial required; this test clears app data and reboots it.');
const execFile = promisify(execFileCallback);
const adb = async (...args: string[]) => (await execFile('adb', ['-s', serial, ...args], { timeout: 180000, maxBuffer: 1024 * 1024 })).stdout;
const dir = await mkdtemp(join(tmpdir(), 'phone-bridge-boot-'));
const config: Config = { host: '127.0.0.1', port: 0, publicUrl: 'http://10.0.2.2:8787', harnessToken: randomBytes(32).toString('base64url'),
  stateFile: join(dir, 'state.json'), requestTimeoutMs: 30000, allowInsecureLan: true };
const bridge = createBridge(config);
const client = new Client({ name: 'external-boot-harness', version: '1' });
const previousAccessibility = (await adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')).trim();
const previousEnabled = (await adb('shell', 'settings', 'get', 'secure', 'accessibility_enabled')).trim();
async function until(check: () => Promise<boolean> | boolean, description: string, seconds = 60) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) { if (await check()) return; await delay(500); }
  throw new Error(`Timed out: ${description}`);
}
async function response(name: string, args: Record<string, unknown> = {}) {
  const result = await client.callTool({ name, arguments: args });
  const content = (result.content as { type: string; text?: string }[])[0];
  assert(content?.type === 'text' && content.text);
  return JSON.parse(content.text);
}
async function tool(name: string, args: Record<string, unknown> = {}) {
  const value = await response(name, args);
  assert.equal(value.ok, true, JSON.stringify(value));
  return value.data;
}
// Android may switch its default network again just after boot. Only observations can
// wait through a reconnect; launch/gesture/input requests still use tool() exactly once.
async function readAfterReconnect(name: 'phone_status' | 'phone_location', args: Record<string, unknown> = {}) {
  let result: Awaited<ReturnType<typeof response>>;
  await until(async () => {
    result = await response(name, args);
    if (result.ok) return true;
    assert(['PHONE_OFFLINE', 'CONNECTION_REPLACED', 'TIMEOUT'].includes(result.error.code), JSON.stringify(result));
    return false;
  }, `${name} response after reconnect`, 60);
  return result.data;
}
try {
  const address = await bridge.listen();
  assert(address && typeof address !== 'string');
  config.publicUrl = `http://10.0.2.2:${address.port}`;
  const localUrl = `http://127.0.0.1:${address.port}`;
  for (const apk of ['app/build/outputs/apk/debug/app-debug.apk', 'demo/build/outputs/apk/debug/demo-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) {
    await adb('install', '-r', resolve('android', apk));
  }
  await adb('shell', 'pm', 'clear', 'io.github.hsd221.phonebridge');
  for (const permission of ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'ACCESS_BACKGROUND_LOCATION', 'POST_NOTIFICATIONS']) {
    await adb('shell', 'pm', 'grant', 'io.github.hsd221.phonebridge', `android.permission.${permission}`);
  }
  const pair = bridge.identities.createPairing();
  const provision = await adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'io.github.hsd221.phonebridge.BootProvisionTest',
    '-e', 'bridgeUrl', config.publicUrl, '-e', 'pairCode', pair.code,
    'io.github.hsd221.phonebridge.test/androidx.test.runner.AndroidJUnitRunner');
  assert(provision.includes('OK (1 test)'), provision);
  await adb('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services');
  await delay(700);
  await adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', 'io.github.hsd221.phonebridge/.PhoneAccessibilityService');
  await adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1');
  // Ordinary setup launch clears instrumentation's stopped process state before the test reboot.
  await adb('shell', 'am', 'start', '-W', '-n', 'io.github.hsd221.phonebridge/.MainActivity');
  await adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  await until(() => bridge.broker.online, 'pre-reboot connection');
  const previousBoot = (await adb('shell', 'cat', '/proc/sys/kernel/random/boot_id')).trim();
  console.log('Provisioned. Rebooting emulator; APK will not be opened after reboot.');
  await adb('reboot');
  await adb('wait-for-device');
  await until(async () => {
    try {
      return (await adb('shell', 'cat', '/proc/sys/kernel/random/boot_id')).trim() !== previousBoot
        && (await adb('shell', 'getprop', 'sys.boot_completed')).trim() === '1';
    } catch { return false; } // ADB can briefly reconnect before Android finishes restarting.
  }, 'Android boot', 120);
  await client.connect(new StreamableHTTPClientTransport(new URL(`${localUrl}/mcp`), { requestInit: { headers: { authorization: `Bearer ${config.harnessToken}` } } }));
  // A pre-reboot TCP socket may look open until its heartbeat expires. Require a phone response.
  await until(async () => {
    const value = await response('phone_status');
    if (value.ok) return value.data.connected === true;
    assert(['PHONE_OFFLINE', 'CONNECTION_REPLACED', 'TIMEOUT'].includes(value.error.code), JSON.stringify(value));
    return false;
  }, 'automatic MCP response after reboot', 90);
  const status = await readAfterReconnect('phone_status');
  assert.equal(status.connected, true);
  assert.equal(status.permissions.accessibility, true);
  assert.equal(status.permissions.locationForegroundService, true);
  await adb('shell', 'input', 'keyevent', 'KEYCODE_MENU'); // Simulates the user unlocking a PIN-free test device.
  const launched = await tool('phone_open_app', { packageName: 'io.github.hsd221.phonebridge.demo' });
  assert.equal(launched.path, 'accessibility');
  await until(async () => {
    const value = await response('phone_ui');
    if (value.ok) return value.data.packageName === 'io.github.hsd221.phonebridge.demo';
    // The accessibility service can briefly expose a window without a root while Android
    // finishes switching activities after a background launch. Reading is safe to retry;
    // action requests are never retried by this harness or by the bridge.
    assert(['WINDOW_UNAVAILABLE', 'UI_UNAVAILABLE', 'APP_CHANGED', 'PHONE_OFFLINE', 'CONNECTION_REPLACED', 'TIMEOUT'].includes(value.error.code), JSON.stringify(value));
    return false;
  }, 'background app launch', 20);
  await adb('emu', 'geo', 'fix', '-74.0060', '40.7128');
  const location = await readAfterReconnect('phone_location', { timeoutMs: 20000 });
  assert(Math.abs(location.latitude - 40.7128) < 0.01);
  console.log('PASS: boot restored connection, accessibility, location FGS, background app launch, UI read, and location through an external MCP harness.');
} finally {
  bridge.identities.revoke(); bridge.broker.disconnect();
  await client.close();
  try {
    if (previousAccessibility === 'null' || previousAccessibility === '') await adb('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services');
    else await adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', previousAccessibility);
    await adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', previousEnabled === 'null' ? '0' : previousEnabled);
  } finally { await bridge.close(); await rm(dir, { recursive: true, force: true }); }
}
