import { existsSync } from 'node:fs';
import { initConfig, loadConfig } from './config.js';

const path = process.env.PHONE_BRIDGE_CONFIG ?? '/data/bridge.config.json';
if (!existsSync(path)) {
  const publicUrl = process.env.PHONE_BRIDGE_PUBLIC_URL;
  if (!publicUrl) throw new Error('Set PHONE_BRIDGE_PUBLIC_URL to the HTTPS address reachable from the phone');
  const insecure = process.env.PHONE_BRIDGE_ALLOW_INSECURE_LAN ?? 'false';
  if (!['true', 'false'].includes(insecure)) throw new Error('PHONE_BRIDGE_ALLOW_INSECURE_LAN must be true or false');
  initConfig(path, {
    host: '0.0.0.0', port: 8787, publicUrl,
    behindProxy: new URL(publicUrl).protocol === 'https:', allowInsecureLan: insecure === 'true',
  });
  console.error(`Created ${path}; configuration and phone identity persist in /data`);
}
loadConfig(path);
