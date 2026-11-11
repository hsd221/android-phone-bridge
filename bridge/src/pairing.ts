import QRCode from 'qrcode';
import { z } from 'zod';
import { loopback } from './config.js';

const pairingSchema = z.object({
  code: z.string().regex(/^[0-9A-F]{16}$/),
  expiresAt: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
}).strict();

export function createPairingPayload(url: string, pairing: { code: string; expiresAt: number }): string {
  const origin = new URL(url);
  if (!['https:', 'http:'].includes(origin.protocol) || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/') {
    throw new Error('Pairing requires an HTTP(S) origin');
  }
  const payload = JSON.stringify({ type: 'phone-bridge-pair', version: 1, url: origin.origin, ...pairingSchema.parse(pairing) });
  if (payload.length > 4096) throw new Error('Pairing payload is too large');
  return payload;
}

export function phoneAddressWarning(url: string): string | undefined {
  const host = new URL(url).hostname;
  if (loopback(host) || ['0.0.0.0', '[::]', '::'].includes(host)) {
    return 'This address points to the phone itself or is a listen address. Set publicUrl to a hostname/IP reachable from the phone before pairing.';
  }
  return undefined;
}

/** Half-height modules keep a normal pairing QR within an 80-column terminal. */
export function renderPairingQr(payload: string): string {
  const modules = QRCode.create(payload, { errorCorrectionLevel: 'M' }).modules;
  const quiet = 4;
  const size = modules.size + quiet * 2;
  const black = (x: number, y: number) => x >= quiet && y >= quiet && x < size - quiet && y < size - quiet
    && !!modules.get(y - quiet, x - quiet);
  const lines: string[] = [];
  for (let y = 0; y < size; y += 2) {
    let line = '\x1b[30;47m';
    for (let x = 0; x < size; x++) {
      const upper = black(x, y); const lower = black(x, y + 1);
      line += upper ? (lower ? '█' : '▀') : (lower ? '▄' : ' ');
    }
    lines.push(`${line}\x1b[0m`);
  }
  return lines.join('\n');
}
