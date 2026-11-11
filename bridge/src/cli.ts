#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { initConfig, loadConfig, localBaseUrl, type Config } from './config.js';
import { createPairingPayload, phoneAddressWarning, renderPairingQr } from './pairing.js';
import { BRIDGE_VERSION } from './protocol.js';
import { createBridge } from './server.js';
import { createMcpServer } from './mcp.js';
import { callBridge } from './client.js';

async function main() {
  const { values, positionals } = parseArgs({ allowPositionals: true,
    options: {
      config: { type: 'string', short: 'c', default: process.env.PHONE_BRIDGE_CONFIG ?? 'bridge.config.json' },
      help: { type: 'boolean', short: 'h' }, version: { type: 'boolean' },
      'public-url': { type: 'string' }, host: { type: 'string' }, port: { type: 'string' },
      'behind-proxy': { type: 'boolean' }, 'allow-insecure-lan': { type: 'boolean' },
      qr: { type: 'boolean' }, 'no-qr': { type: 'boolean' }, json: { type: 'boolean' },
    } });
  const command = positionals[0];
  if (values.version) { console.log(BRIDGE_VERSION); return; }
  if (values.help || !command) {
    console.log(`phone-bridge <init|serve|pair|status|revoke|mcp> --config bridge.config.json
  init: --public-url URL --host ADDRESS --port NUMBER --behind-proxy --allow-insecure-lan
  pair: --qr (force terminal QR), --no-qr, --json
  --version`);
    return;
  }
  if (positionals.length > 1) throw new Error('Unexpected positional arguments');
  if (values.qr && (values['no-qr'] || values.json)) throw new Error('--qr cannot be combined with --no-qr or --json');
  if (command !== 'init' && [values['public-url'], values.host, values.port, values['behind-proxy'], values['allow-insecure-lan']].some(value => value !== undefined)) {
    throw new Error('Configuration options apply to init only; edit the config file to change an existing deployment');
  }
  if (command !== 'pair' && [values.qr, values['no-qr'], values.json].some(value => value !== undefined)) throw new Error('Output options apply to pair only');
  if (command === 'init') {
    initConfig(values.config!, {
      ...(values['public-url'] !== undefined ? { publicUrl: values['public-url'] } : {}),
      ...(values.host !== undefined ? { host: values.host } : {}),
      ...(values.port !== undefined ? { port: Number(values.port) } : {}),
      ...(values['behind-proxy'] !== undefined ? { behindProxy: values['behind-proxy'] } : {}),
      ...(values['allow-insecure-lan'] !== undefined ? { allowInsecureLan: values['allow-insecure-lan'] } : {}),
    });
    console.log(`Created ${values.config}; publicUrl must be reachable from the phone`); return;
  }
  const config = loadConfig(values.config!);
  const baseUrl = localBaseUrl(config);
  if (command === 'serve') {
    const bridge = createBridge(config);
    await bridge.listen();
    console.error(`Bridge listening on ${config.host}:${config.port}; phone address ${config.publicUrl}`);
    const stop = () => { void bridge.close().then(() => process.exit(0)); };
    process.once('SIGINT', stop); process.once('SIGTERM', stop);
    return;
  }
  if (command === 'mcp') {
    const server = createMcpServer((tool, args, signal) => callBridge(baseUrl, config, tool, args, signal));
    await server.connect(new StdioServerTransport());
    return;
  }
  const endpoints = { pair: '/admin/pair', status: '/admin/status', revoke: '/admin/revoke' };
  if (!Object.hasOwn(endpoints, command)) throw new Error(`Unknown command: ${command}`);
  const endpoint = endpoints[command as keyof typeof endpoints];
  const response = await fetch(`${baseUrl}${endpoint}`, { method: command === 'status' ? 'GET' : 'POST', headers: authHeaders(config), signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`Bridge HTTP ${response.status}: ${await response.text()}`);
  const data = await response.json();
  if (command !== 'pair') { console.log(JSON.stringify(data, null, 2)); return; }
  const payload = createPairingPayload(config.publicUrl, data as { code: string; expiresAt: number });
  if (values.json) { console.log(JSON.stringify(JSON.parse(payload))); return; }
  const pairing = JSON.parse(payload) as { code: string; expiresAt: number };
  console.log(`Phone Bridge address: ${config.publicUrl}\nPairing code: ${pairing.code}\nExpires: ${new Date(pairing.expiresAt).toISOString()} (single use)`);
  const warning = phoneAddressWarning(config.publicUrl);
  if (warning) console.error(warning);
  if (!values['no-qr'] && (values.qr || process.stdout.isTTY)) {
    console.log(`\n${renderPairingQr(payload)}\nScan in Phone Bridge, then confirm the address on the phone.`);
  }
}
function authHeaders(config: Config) { return { authorization: `Bearer ${config.harnessToken}`, 'content-type': 'application/json' }; }
main().catch(error => { console.error(error instanceof Error ? error.message : 'Command failed'); process.exitCode = 1; });
