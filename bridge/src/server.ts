import { createServer as createHttpServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { readFileSync } from 'node:fs';
import { WebSocketServer, WebSocket } from 'ws';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';
import { PhoneBroker } from './broker.js';
import { IdentityStore, secureEqual } from './identity.js';
import { createMcpServer } from './mcp.js';
import { isTool } from './protocol.js';
import type { Config } from './config.js';

class HttpError extends Error { constructor(public status: number, message: string) { super(message); } }
const bearer = (req: IncomingMessage) => req.headers.authorization?.startsWith('Bearer ') ? req.headers.authorization.slice(7) : '';
async function body(req: IncomingMessage): Promise<unknown> {
  if (!req.headers['content-type']?.startsWith('application/json')) throw new HttpError(415, 'JSON required');
  const chunks: Buffer[] = [];
  let bytes = 0;
  for await (const chunk of req) {
    bytes += chunk.length;
    if (bytes > 65536) throw new HttpError(413, 'Request too large');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new HttpError(400, 'Invalid JSON'); }
}
function json(res: ServerResponse, status: number, value: unknown) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
  res.end(JSON.stringify(value));
}
export function createBridge(config: Config) {
  const identities = new IdentityStore(config.stateFile);
  const broker = new PhoneBroker(config.requestTimeoutMs);
  const wss = new WebSocketServer({ noServer: true, maxPayload: 12 * 1024 * 1024, perMessageDeflate: false });
  const server = config.tls ? createHttpsServer({ cert: readFileSync(config.tls.certFile), key: readFileSync(config.tls.keyFile) }) : createHttpServer();
  let pairAttempts = 0;
  let attemptReset = Date.now() + 60000;
  const allowedOrigins = new Set([new URL(config.publicUrl).origin]);
  const validRequestOrigin = (req: IncomingMessage) => {
    const address = server.address();
    const port = address && typeof address !== 'string' ? address.port : config.port;
    const hosts = new Set([new URL(config.publicUrl).host, `${config.host}:${port}`, `127.0.0.1:${port}`, `localhost:${port}`, `[::1]:${port}`]);
    return !!req.headers.host && hosts.has(req.headers.host) && (!req.headers.origin || allowedOrigins.has(req.headers.origin));
  };
  const requireHarness = (req: IncomingMessage) => {
    if (!secureEqual(bearer(req), config.harnessToken)) throw new HttpError(401, 'Harness authentication required');
  };
  server.on('request', (req, res) => { void (async () => {
    if (!validRequestOrigin(req)) throw new HttpError(403, 'Host or Origin rejected');
    const path = new URL(req.url ?? '/', config.publicUrl).pathname;
    if (path === '/health' && req.method === 'GET') return json(res, 200, { ok: true, protocol: 1 });
    if (path === '/pair' && req.method === 'POST') {
      if (Date.now() >= attemptReset) { pairAttempts = 0; attemptReset = Date.now() + 60000; }
      if (++pairAttempts > 30) throw new HttpError(429, 'Too many pairing attempts; wait one minute');
      const input = z.object({ code: z.string().min(1).max(32), deviceId: z.string().uuid(), label: z.string().min(1).max(80) }).strict().parse(await body(req));
      const identity = identities.pair(input.code, input.deviceId, input.label);
      if (!identity) throw new HttpError(403, 'Pairing code invalid, expired, used, or another phone is paired');
      return json(res, 200, identity);
    }
    if (path === '/device/revoke' && req.method === 'DELETE') {
      if (!identities.authenticate(bearer(req))) throw new HttpError(401, 'Phone authentication required');
      identities.revoke();
      broker.disconnect();
      return json(res, 200, { ok: true });
    }
    if (path.startsWith('/admin/') || path === '/mcp' || path === '/api/call') requireHarness(req);
    if (path === '/admin/pair' && req.method === 'POST') {
      if (identities.device) throw new HttpError(409, 'Phone already paired; revoke first');
      return json(res, 200, identities.createPairing());
    }
    if (path === '/admin/revoke' && req.method === 'POST') {
      identities.revoke(); broker.disconnect(); return json(res, 200, { ok: true });
    }
    if (path === '/admin/status' && req.method === 'GET') {
      const device = identities.device;
      return json(res, 200, { online: broker.online, device: device ? { deviceId: device.deviceId, label: device.label, pairedAt: device.pairedAt } : null });
    }
    if (path === '/api/call' && req.method === 'POST') {
      const input = z.object({ tool: z.string(), args: z.unknown() }).strict().parse(await body(req));
      if (!isTool(input.tool)) throw new HttpError(400, 'Unknown tool');
      const abort = new AbortController();
      res.once('close', () => { if (!res.writableEnded) abort.abort(); });
      return json(res, 200, await broker.call(input.tool, input.args, abort.signal));
    }
    if (path === '/mcp') {
      if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); return json(res, 405, { error: 'Use stateless MCP POST' }); }
      const parsedBody = await body(req);
      const mcp = createMcpServer((tool, args, signal) => broker.call(tool, args, signal));
      const transport = new StreamableHTTPServerTransport({ enableJsonResponse: true });
      res.once('close', () => { void transport.close(); void mcp.close(); });
      await mcp.connect(transport);
      await transport.handleRequest(req, res, parsedBody);
      return;
    }
    json(res, 404, { error: 'Not found' });
  })().catch(error => {
    if (res.headersSent || res.destroyed) return;
    const status = error instanceof HttpError ? error.status : error instanceof z.ZodError ? 400 : 500;
    json(res, status, { error: status === 500 ? 'Internal bridge error' : error.message });
  }); });
  server.on('upgrade', (req, socket, head) => {
    if (!validRequestOrigin(req) || req.url !== '/device' || !identities.authenticate(bearer(req))) {
      socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return;
    }
    const deviceId = identities.device!.deviceId;
    wss.handleUpgrade(req, socket, head, ws => {
      broker.attach(ws, deviceId);
      let alive = true;
      ws.on('pong', () => { alive = true; });
      const heartbeat = setInterval(() => {
        if (!alive) { ws.terminate(); return; }
        alive = false;
        if (ws.readyState === WebSocket.OPEN) ws.ping();
      }, 25000);
      ws.on('close', () => clearInterval(heartbeat));
    });
  });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  return {
    server, broker, identities,
    async listen() {
      await new Promise<void>((resolve, reject) => {
        server.once('error', reject);
        server.listen(config.port, config.host, () => { server.removeListener('error', reject); resolve(); });
      });
      return server.address();
    },
    async close() {
      broker.disconnect('BRIDGE_STOPPED', 'Bridge stopped; requests were not replayed');
      for (const client of wss.clients) client.terminate();
      await new Promise<void>(resolve => wss.close(() => resolve()));
      server.closeIdleConnections();
      await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    },
  };
}
