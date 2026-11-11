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
  behindProxy: z.boolean().optional(),
  tls: z.object({ certFile: z.string(), keyFile: z.string() }).strict().optional(),
}).strict();
export type Config = z.infer<typeof configSchema>;
export const loopback = (host: string) => ['localhost', '127.0.0.1', '::1', '[::1]'].includes(host);
function validateConfig(value: unknown): Config {
  const config = configSchema.parse(value);
  const url = new URL(config.publicUrl);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('publicUrl must be an HTTP(S) origin without credentials, path, or query');
  }
  if (!config.allowInsecureLan && (url.protocol !== 'https:' && !loopback(url.hostname))) {
    throw new Error('Remote publicUrl requires HTTPS; allowInsecureLan is only for explicit development use');
  }
  if (config.behindProxy && url.protocol !== 'https:') {
    throw new Error('behindProxy requires an HTTPS publicUrl');
  }
  if (!config.tls && !loopback(config.host) && !config.allowInsecureLan && !config.behindProxy) {
    throw new Error('Bind plaintext to loopback behind a TLS proxy, configure tls, or explicitly set behindProxy');
  }
  return config;
}
export function loadConfig(path: string): Config {
  const config = validateConfig(JSON.parse(readFileSync(path, 'utf8')));
  const base = dirname(resolve(path));
  config.stateFile = resolve(base, config.stateFile);
  if (config.tls) {
    config.tls.certFile = resolve(base, config.tls.certFile);
    config.tls.keyFile = resolve(base, config.tls.keyFile);
  }
  return config;
}
/** CLI management stays inside the host/container when TLS terminates at a proxy. */
export function localBaseUrl(config: Config): string {
  if (config.tls) return config.publicUrl;
  const host = config.host === '0.0.0.0' ? '127.0.0.1' : config.host === '::' ? '::1' : config.host;
  return `http://${host.includes(':') && !host.startsWith('[') ? `[${host}]` : host}:${config.port}`;
}
export function initConfig(path: string, options: Partial<Pick<Config, 'host' | 'port' | 'publicUrl' | 'behindProxy' | 'allowInsecureLan'>> = {}) {
  const config = validateConfig({ host: '127.0.0.1', port: 8787, publicUrl: 'http://127.0.0.1:8787',
    harnessToken: randomBytes(32).toString('base64url'), stateFile: 'bridge-state.json',
    requestTimeoutMs: 30000, allowInsecureLan: false, ...options });
  writeFileSync(path, JSON.stringify(config, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
}
