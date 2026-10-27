#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { initConfig, loadConfig, loopback, type Config } from './config.js';
import { createBridge } from './server.js';
import { createMcpServer } from './mcp.js';
import { failure, resultSchema } from './protocol.js';

async function main() {
  const { values, positionals } = parseArgs({ allowPositionals: true,
    options: { config: { type: 'string', short: 'c', default: 'bridge.config.json' }, help: { type: 'boolean', short: 'h' } } });
  const command = positionals[0];
  if (values.help || !command) {
    console.log('phone-bridge <init|serve|pair|status|revoke|mcp> --config bridge.config.json');
    return;
  }
  if (positionals.length > 1) throw new Error('Unexpected positional arguments');
  if (command === 'init') { initConfig(values.config!); console.log(`Created ${values.config}; edit publicUrl before remote pairing`); return; }
  const config = loadConfig(values.config!);
  const baseUrl = loopback(config.host) ? `${config.tls ? 'https' : 'http'}://${config.host === '::1' ? '[::1]' : config.host}:${config.port}` : config.publicUrl;
  if (command === 'serve') {
    const bridge = createBridge(config);
    await bridge.listen();
    console.error(`Bridge listening on ${config.host}:${config.port}; phone address ${config.publicUrl}`);
    const stop = () => { void bridge.close().then(() => process.exit(0)); };
    process.once('SIGINT', stop); process.once('SIGTERM', stop);
    return;
  }
  if (command === 'mcp') {
    const server = createMcpServer(async (tool, args, signal) => {
      try {
        const response = await fetch(`${baseUrl}/api/call`, { method: 'POST',
          headers: authHeaders(config), body: JSON.stringify({ tool, args }),
          signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(config.requestTimeoutMs + 3000)]) : AbortSignal.timeout(config.requestTimeoutMs + 3000) });
        if (!response.ok) return failure('BRIDGE_UNAVAILABLE', `Bridge returned HTTP ${response.status}`);
        return resultSchema.parse(await response.json());
      } catch { return failure('BRIDGE_UNAVAILABLE', 'Could not reach Bridge; do not automatically repeat an action with an unknown result', true); }
    });
    await server.connect(new StdioServerTransport());
    return;
  }
  const endpoints = { pair: '/admin/pair', status: '/admin/status', revoke: '/admin/revoke' };
  if (!Object.hasOwn(endpoints, command)) throw new Error(`Unknown command: ${command}`);
  const endpoint = endpoints[command as keyof typeof endpoints];
  const response = await fetch(`${baseUrl}${endpoint}`, { method: command === 'status' ? 'GET' : 'POST', headers: authHeaders(config), signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`Bridge HTTP ${response.status}: ${await response.text()}`);
  const data = await response.json();
  if (command === 'pair') console.log(`Phone Bridge address: ${config.publicUrl}\n${JSON.stringify(data, null, 2)}`);
  else console.log(JSON.stringify(data, null, 2));
}
function authHeaders(config: Config) { return { authorization: `Bearer ${config.harnessToken}`, 'content-type': 'application/json' }; }
main().catch(error => { console.error(error instanceof Error ? error.message : 'Command failed'); process.exitCode = 1; });
