import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { z } from 'zod';

export const digest = (value: string) => createHash('sha256').update(value).digest('hex');
export function secureEqual(a: string, b: string) { return timingSafeEqual(Buffer.from(digest(a)), Buffer.from(digest(b))); }
const deviceSchema = z.object({ deviceId: z.string().uuid(), label: z.string().min(1).max(80), tokenHash: z.string().length(64), pairedAt: z.string() }).strict();
const stateSchema = z.object({ version: z.literal(1), device: deviceSchema.nullable() }).strict();
export class IdentityStore {
  private state: z.infer<typeof stateSchema>;
  private pairing: { hash: string; expiresAt: number } | undefined;
  constructor(private path: string) {
    this.state = existsSync(path) ? stateSchema.parse(JSON.parse(readFileSync(path, 'utf8'))) : { version: 1, device: null };
  }
  get device() { return this.state.device; }
  createPairing(ttlMs = 300000) {
    if (this.device) throw new Error('A phone is already paired. Unpair it or run revoke first.');
    const code = randomBytes(8).toString('hex').toUpperCase();
    const expiresAt = Date.now() + ttlMs;
    this.pairing = { hash: digest(code), expiresAt };
    return { code, expiresAt };
  }
  pair(code: string, deviceId: string, label: string) {
    if (this.device || !this.pairing || this.pairing.expiresAt <= Date.now() || !secureEqual(digest(code.toUpperCase()), this.pairing.hash)) return null;
    const token = randomBytes(32).toString('base64url');
    const device = deviceSchema.parse({ deviceId, label, tokenHash: digest(token), pairedAt: new Date().toISOString() });
    this.persist({ version: 1, device });
    this.pairing = undefined;
    return { deviceId, token, protocol: 1 };
  }
  authenticate(token: string) { return !!this.device && secureEqual(digest(token), this.device.tokenHash); }
  revoke() { this.persist({ version: 1, device: null }); this.pairing = undefined; }
  private persist(state: z.infer<typeof stateSchema>) {
    const temp = `${this.path}.tmp`;
    writeFileSync(temp, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
    renameSync(temp, this.path);
    this.state = state;
  }
}
