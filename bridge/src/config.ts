import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { z } from 'zod';

const configSchema = z.object({
  host: z.string().default('127.0.0.1'),
  port: z.number().int().min(0).max(65535).default(8787),
  publicUrl: z.string().url(),
  harnessToken: z.string().min(32),
  stateFile: z.string().default('bridge-state.json'),
  requestTimeoutMs: z.number().int().min(1000).max(60000).default(30000),
  allowInsecureLan: z.boolean().default(false),
  tls: z.object({ certFile: z.string(), keyFile: z.string() }).strict().optional(),
}).strict();
export type Config = z.infer<typeof configSchema>;
export const loopback = (host: string) => ['localhost', '127.0.0.1', '::1', '[::1]'].includes(host);
export function loadConfig(path: string): Config {
  const config = configSchema.parse(JSON.parse(readFileSync(path, 'utf8')));
  const url = new URL(config.publicUrl);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('publicUrl must be an HTTP(S) origin without credentials, path, or query');
  }
  if (!config.allowInsecureLan && (url.protocol !== 'https:' && !loopback(url.hostname))) {
    throw new Error('Remote publicUrl requires HTTPS; allowInsecureLan is only for explicit development use');
  }
  if (!config.tls && !loopback(config.host) && !config.allowInsecureLan) {
    throw new Error('Bind plaintext to loopback behind a TLS proxy, or configure tls');
  }
  const base = dirname(resolve(path));
  config.stateFile = resolve(base, config.stateFile);
  if (config.tls) {
    config.tls.certFile = resolve(base, config.tls.certFile);
    config.tls.keyFile = resolve(base, config.tls.keyFile);
  }
  return config;
}
export function initConfig(path: string) {
  const config: Config = { host: '127.0.0.1', port: 8787, publicUrl: 'http://127.0.0.1:8787',
    harnessToken: randomBytes(32).toString('base64url'), stateFile: 'bridge-state.json',
    requestTimeoutMs: 30000, allowInsecureLan: false };
  writeFileSync(path, JSON.stringify(config, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
}
