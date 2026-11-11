// Node 22 SEA procedure: https://nodejs.org/docs/latest-v22.x/api/single-executable-applications.html
import { build } from 'esbuild';
import { inject } from 'postject';
import { chmod, copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';

if (Number(process.versions.node.split('.')[0]) !== 22) throw new Error('Build single-file distributions with Node.js 22');
if (!['linux', 'win32'].includes(process.platform)) throw new Error('Binary builds currently support Linux and Windows');
const root = resolve(import.meta.dirname, '..');
const pkg = JSON.parse(await readFile(resolve(root, 'package.json'), 'utf8'));
const outputDir = resolve(root, 'artifacts');
const staging = resolve(root, 'dist', 'sea');
await mkdir(outputDir, { recursive: true });
await mkdir(staging, { recursive: true });
const bundled = resolve(staging, 'cli.cjs');
await build({
  absWorkingDir: root, entryPoints: ['bridge/src/cli.ts'], bundle: true, platform: 'node',
  target: 'node22', format: 'cjs', outfile: bundled, legalComments: 'inline',
  // ws has efficient JS fallbacks. Optional native addons must never resolve from the user's disk.
  alias: { bufferutil: './scripts/sea-no-addon.cjs', 'utf-8-validate': './scripts/sea-no-addon.cjs' },
});
const blob = resolve(staging, 'sea-prep.blob');
const config = resolve(staging, 'sea-config.json');
await writeFile(config, JSON.stringify({ main: bundled, output: blob, disableExperimentalSEAWarning: true, useSnapshot: false, useCodeCache: false }));
execFileSync(process.execPath, ['--experimental-sea-config', config], { cwd: root, stdio: 'inherit' });
const filename = `phone-bridge-${pkg.version}-${process.platform === 'win32' ? 'windows' : 'linux'}-${process.arch}${process.platform === 'win32' ? '.exe' : ''}`;
const executable = resolve(outputDir, filename);
await copyFile(process.execPath, executable);
await chmod(executable, 0o755);
await inject(executable, 'NODE_SEA_BLOB', await readFile(blob), { sentinelFuse: 'NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2' });
const digest = createHash('sha256').update(await readFile(executable)).digest('hex');
await writeFile(resolve(outputDir, `${filename}.sha256`), `${digest}  ${filename}\n`);
console.log(`Built ${executable}`);
