import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const serial = process.argv[2];
if (!serial || !/^emulator-\d+$/.test(serial)) throw new Error('Explicit dedicated emulator serial required; this test resets app data and temporarily sets synthetic lock credentials.');
const execFile = promisify(execFileCallback);
const adb = async (...args: string[]) => (await execFile('adb', ['-s', serial, ...args], { timeout: 240000, maxBuffer: 1024 * 1024 })).stdout;
const apkUrl = 'https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk';
const apkSha256 = '6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f';
const dir = await mkdtemp(join(tmpdir(), 'phone-bridge-shizuku-unlock-'));
const cached = process.env.PHONE_BRIDGE_SHIZUKU_APK;
const apkPath = cached ?? join(dir, 'shizuku.apk');
let ownedPid: string | undefined;
try {
  if (!cached) {
    const response = await fetch(apkUrl, { signal: AbortSignal.timeout(60000) });
    assert(response.ok, `Pinned Shizuku APK download failed: ${response.status}`);
    await writeFile(apkPath, new Uint8Array(await response.arrayBuffer()));
  }
  assert.equal(createHash('sha256').update(await readFile(apkPath)).digest('hex'), apkSha256, 'Pinned Shizuku APK digest mismatch');
  for (const apk of [apkPath, resolve('android/app/build/outputs/apk/debug/app-debug.apk'), resolve('android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]) {
    await adb('install', '-r', apk);
  }
  await adb('shell', 'pm', 'clear', 'io.github.hsd221.phonebridge');
  await adb('shell', 'pm', 'grant', 'io.github.hsd221.phonebridge', 'android.permission.POST_NOTIFICATIONS');
  // Shizuku 13.6 checks this Android runtime permission for targetSdk>=23.
  // Grant it only on the isolated emulator, before starting instrumentation.
  await adb('shell', 'pm', 'grant', 'io.github.hsd221.phonebridge', 'moe.shizuku.manager.permission.API_V23');
  assert.equal((await adb('shell', 'pidof', 'shizuku_server').catch(() => '')).trim(), '', 'Refusing to take over a Shizuku server not started by this test');
  const installed = (await adb('shell', 'pm', 'path', 'moe.shizuku.privileged.api')).trim();
  const baseApk = installed.split('\n').find(line => line.endsWith('/base.apk'))?.replace(/^package:/, '');
  assert(baseApk && /^\/data\/app\/[A-Za-z0-9_+~=/.-]+\/base\.apk$/.test(baseApk), 'Unexpected installed Shizuku APK path');
  const nativeStarter = `${baseApk.slice(0, -'/base.apk'.length)}/lib/x86_64/libshizuku.so`;
  await adb('shell', nativeStarter, `--apk=${baseApk}`);
  let pid = '';
  for (let attempt = 0; attempt < 100; attempt++) {
    pid = (await adb('shell', 'pidof', 'shizuku_server').catch(() => '')).trim();
    if (pid) break;
    await delay(100);
  }
  assert(/^\d+$/.test(pid), 'Real Shizuku server did not start');
  ownedPid = pid;
  const status = await adb('shell', 'cat', `/proc/${pid}/status`);
  assert(/^Uid:\s+2000\s+2000\s+2000\s+2000$/m.test(status), 'Shizuku did not start with non-root shell UID 2000');
  const result = await adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'io.github.hsd221.phonebridge.ShizukuUnlockTest#realShellInputAndDisconnection',
    '-e', 'shizukuUnlockTest', 'true', 'io.github.hsd221.phonebridge.test/androidx.test.runner.AndroidJUnitRunner');
  process.stdout.write(result);
  assert(result.includes('OK (1 test)') && !result.includes('FAILURES'), 'Real Shizuku unlock instrumentation failed');
} finally {
  // Only emulator processes started for this suite are cleaned up. No host or physical-device adb.
  const running = (await adb('shell', 'pidof', 'shizuku_server').catch(() => '')).trim();
  if (ownedPid && running === ownedPid) await adb('shell', 'kill', running).catch(() => undefined);
  await rm(dir, { recursive: true, force: true });
}
