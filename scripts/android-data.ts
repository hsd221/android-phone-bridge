import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createHash, randomBytes } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { createBridge } from '../bridge/src/server.js';
import type { Config } from '../bridge/src/config.js';

const serial = process.argv[2];
if (!serial || !/^emulator-\d+$/.test(serial) || process.argv.length !== 3) throw new Error('Explicit dedicated emulator serial required. This test clears test-app data and writes only synthetic media and clipboard data.');
const execFile = promisify(execFileCallback);
const adb = async (...args: string[]) => {
  try { return (await execFile('adb', ['-s', serial, ...args], { timeout: 240000, maxBuffer: 1024 * 1024 })).stdout; }
  catch { throw new Error(`Dedicated emulator command failed (${args[0]}); instrumentation arguments are intentionally omitted.`); }
};
const dir = await mkdtemp(join(tmpdir(), 'phone-bridge-data-'));
const token = randomBytes(32).toString('base64url');
const config: Config = { host: '127.0.0.1', port: 0, publicUrl: 'http://10.0.2.2:8787', harnessToken: token,
  stateFile: join(dir, 'state.json'), requestTimeoutMs: 30000, allowInsecureLan: true };
const bridge = createBridge(config);
const cached = process.env.PHONE_BRIDGE_SHIZUKU_APK;
const apk = cached ?? join(dir, 'shizuku.apk');
let ownedPid: string | undefined;
const reset = async () => {
  await adb('shell', 'pm', 'clear', 'io.github.hsd221.phonebridge');
  await adb('shell', 'pm', 'grant', 'io.github.hsd221.phonebridge', 'android.permission.POST_NOTIFICATIONS');
};
const instrument = async (name: string, pid = '') => {
  const pair = bridge.identities.createPairing();
  const output = await adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', `io.github.hsd221.phonebridge.${name}`,
    '-e', 'dataToolTest', 'true', '-e', 'bridgeUrl', config.publicUrl, '-e', 'pairCode', pair.code,
    '-e', 'harnessToken', token, ...(pid ? ['-e', 'dataShizukuServerPid', pid] : []),
    'io.github.hsd221.phonebridge.test/androidx.test.runner.AndroidJUnitRunner');
  process.stdout.write(output);
  assert(output.includes('OK (1 test)') && !output.includes('FAILURES'), `${name} failed`);
};
try {
  assert.equal((await adb('shell', 'getprop', 'ro.kernel.qemu')).trim(), '1', 'Dedicated emulator required');
  assert.equal((await adb('shell', 'pidof', 'shizuku_server').catch(() => '')).trim(), '', 'Refusing to take over a Shizuku server not started by this suite');
  const address = await bridge.listen();
  assert(address && typeof address !== 'string');
  config.publicUrl = `http://10.0.2.2:${address.port}`;
  for (const path of [resolve('android/app/build/outputs/apk/debug/app-debug.apk'), resolve('android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]) await adb('install', '-r', path);
  await reset();
  await instrument('MediaReadTest');
  if (!cached) {
    const response = await fetch('https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk', { signal: AbortSignal.timeout(60000) });
    assert(response.ok, `Pinned Shizuku download failed: ${response.status}`);
    await writeFile(apk, new Uint8Array(await response.arrayBuffer()));
  }
  assert.equal(createHash('sha256').update(await readFile(apk)).digest('hex'), '6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f', 'Pinned Shizuku APK digest mismatch');
  await adb('install', '-r', apk);
  await reset();
  await adb('shell', 'pm', 'grant', 'io.github.hsd221.phonebridge', 'moe.shizuku.manager.permission.API_V23');
  const installed = (await adb('shell', 'pm', 'path', 'moe.shizuku.privileged.api')).trim();
  const baseApk = installed.split('\n').find(line => line.endsWith('/base.apk'))?.replace(/^package:/, '');
  assert(baseApk && /^\/data\/app\/[A-Za-z0-9_+~=/.-]+\/base\.apk$/.test(baseApk), 'Unexpected installed Shizuku path');
  await adb('shell', `${baseApk.slice(0, -'/base.apk'.length)}/lib/x86_64/libshizuku.so`, `--apk=${baseApk}`);
  for (let attempt = 0; attempt < 100; attempt++) {
    const pid = (await adb('shell', 'pidof', 'shizuku_server').catch(() => '')).trim();
    if (/^\d+$/.test(pid)) { ownedPid = pid; break; }
    await delay(100);
  }
  assert(ownedPid, 'Real Shizuku server did not start');
  assert(/^Uid:\s+2000\s+2000\s+2000\s+2000$/m.test(await adb('shell', 'cat', `/proc/${ownedPid}/status`)), 'Shizuku must run as shell UID 2000');
  await instrument('ClipboardTest', ownedPid);
} finally {
  try {
    const running = (await adb('shell', 'pidof', 'shizuku_server').catch(() => '')).trim();
    if (ownedPid && running === ownedPid) await adb('shell', 'kill', running);
  } finally {
    try { await bridge.close(); }
    finally { await rm(dir, { recursive: true, force: true }); }
  }
}
