import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { deflateSync } from 'node:zlib';
import { createPairingPayload, renderPairingQr } from '../bridge/src/pairing.js';

// Synthetic inputs only. This script never loads deployment configuration or contacts a Bridge.
const { values, positionals } = parseArgs({ allowPositionals: true, options: {
  'center-x': { type: 'string', default: '360' }, 'center-y': { type: 'string', default: '480' },
  scale: { type: 'string', default: '6' },
} });
if (positionals.length !== 1) throw new Error('Usage: npx tsx scripts/generate-qr-camera-fixture.ts <output.png> [--center-x 360 --center-y 480 --scale 6]');
const payload = createPairingPayload('https://bridge.example.com', { code: '0123456789ABCDEF', expiresAt: Date.now() + 3600000 });
const ansi = renderPairingQr(payload);
const rows = ansi.split('\n').map(row => row.replace(/\x1b\[[0-9;]*m/g, ''));
const columns = rows[0]!.length;
const width = 1280; const height = 960;
const scale = Number(values.scale); const centerX = Number(values['center-x']); const centerY = Number(values['center-y']);
if (![scale, centerX, centerY].every(Number.isInteger) || scale < 1) throw new Error('Fixture geometry must contain integer pixel values');
const left = centerX - Math.floor(columns * scale / 2); const top = centerY - rows.length * scale;
if (left < 0 || top < 0 || left + columns * scale > width || top + rows.length * 2 * scale > height) throw new Error('QR fixture does not fit the image');
const pixels = Buffer.alloc((width + 1) * height, 255);
for (let y = 0; y < height; y++) pixels[y * (width + 1)] = 0; // PNG filter: None.
const black = (x: number, y: number) => {
  for (let dy = 0; dy < scale; dy++) {
    const start = (top + y * scale + dy) * (width + 1) + 1 + left + x * scale;
    pixels.fill(0, start, start + scale);
  }
};
rows.forEach((row, y) => {
  if (row.length !== columns) throw new Error('ANSI QR rows have inconsistent widths');
  Array.from(row).forEach((character, x) => {
    if (!' ▀▄█'.includes(character)) throw new Error('Unexpected terminal QR character');
    if ('▀█'.includes(character)) black(x, y * 2);
    if ('▄█'.includes(character)) black(x, y * 2 + 1);
  });
});

// Encode a grayscale PNG using Node built-ins; no image or camera packages are needed.
function chunk(type: string, data: Buffer): Buffer {
  const content = Buffer.concat([Buffer.from(type), data]);
  let crc = 0xffffffff;
  for (const value of content) {
    crc ^= value;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  const size = Buffer.alloc(4); size.writeUInt32BE(data.length);
  const checksum = Buffer.alloc(4); checksum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
  return Buffer.concat([size, content, checksum]);
}
const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8;
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]);
const output = resolve(positionals[0]!);
await mkdir(dirname(output), { recursive: true });
await writeFile(output, png);
await writeFile(`${output}.ansi`, ansi);
console.log(`Wrote synthetic terminal QR camera fixture: ${output} (${width}x${height}, valid for one hour)`);
