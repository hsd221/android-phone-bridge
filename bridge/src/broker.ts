import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import { z } from 'zod';
import { failure, isOperation, resultSchema, toolInputs, type ToolName, type ToolResult } from './protocol.js';

type Pending = { socket: WebSocket; tool: ToolName; resolve: (result: ToolResult) => void; timer: NodeJS.Timeout; cleanup: () => void };
export class PhoneBroker {
  private socket: WebSocket | undefined;
  private deviceId: string | undefined;
  private ready = false;
  private pending = new Map<string, Pending>();
  constructor(private timeoutMs: number) {}
  get online() { return this.ready && this.socket?.readyState === WebSocket.OPEN; }
  attach(socket: WebSocket, deviceId: string) {
    this.disconnect('CONNECTION_REPLACED', 'Phone reconnected; requests were not replayed');
    this.socket = socket;
    this.deviceId = deviceId;
    this.ready = false;
    const helloTimer = setTimeout(() => socket.close(1008, 'Hello required'), 10000);
    socket.on('message', raw => {
      try {
        if (socket !== this.socket) return;
        const message: unknown = JSON.parse(raw.toString());
        const hello = z.object({ type: z.literal('hello'), protocol: z.literal(1), deviceId: z.literal(deviceId) }).passthrough().safeParse(message);
        if (hello.success) { this.ready = true; clearTimeout(helloTimer); return; }
        if (!this.ready || socket !== this.socket) return;
        const parsed = z.object({ type: z.literal('result'), id: z.string().uuid(), deviceId: z.literal(deviceId), result: resultSchema }).strict().parse(message);
        const pending = this.pending.get(parsed.id);
        if (!pending || pending.socket !== socket) return;
        this.finish(parsed.id, parsed.result);
      } catch { socket.close(1008, 'Invalid protocol message'); }
    });
    socket.on('close', () => {
      clearTimeout(helloTimer);
      if (this.socket === socket) this.disconnect('PHONE_OFFLINE', 'Phone disconnected; requests were not replayed');
    });
    socket.on('error', () => socket.terminate());
  }
  async call(tool: ToolName, args: unknown, signal?: AbortSignal): Promise<ToolResult> {
    const parsed = toolInputs[tool].safeParse(args);
    if (!parsed.success) return failure('INVALID_ARGUMENT', parsed.error.issues.map(i => i.message).join('; '));
    const socket = this.socket;
    if (!this.online || !socket || !this.deviceId) return failure('PHONE_OFFLINE', 'The paired phone is not connected');
    if (signal?.aborted) return failure('CANCELLED', 'Harness cancelled the request');
    if (this.pending.size >= 8) return failure('BUSY', 'Phone request queue is full');
    const id = randomUUID();
    const issuedAt = Date.now();
    return new Promise(resolve => {
      const cancel = () => {
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'cancel', id }));
        this.finish(id, failure('CANCELLED', 'Harness cancelled; an already dispatched action may have run', isOperation(tool)));
      };
      const timer = setTimeout(() => {
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'cancel', id }));
        this.finish(id, failure('TIMEOUT', 'Phone did not finish before the deadline; do not automatically retry actions', isOperation(tool)));
      }, this.timeoutMs);
      this.pending.set(id, { socket, tool, resolve, timer, cleanup: () => signal?.removeEventListener('abort', cancel) });
      signal?.addEventListener('abort', cancel, { once: true });
      socket.send(JSON.stringify({ type: 'request', protocol: 1, deviceId: this.deviceId, id, issuedAt,
        expiresAt: issuedAt + this.timeoutMs, tool, args: parsed.data }), error => {
        if (error) this.finish(id, failure('PHONE_OFFLINE', 'Request delivery failed', isOperation(tool)));
      });
    });
  }
  disconnect(code = 'REVOKED', message = 'Phone identity was revoked') {
    const socket = this.socket;
    this.socket = undefined;
    this.ready = false;
    for (const [id, pending] of this.pending) this.finish(id, failure(code, message, isOperation(pending.tool)));
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(4001, code);
  }
  private finish(id: string, result: ToolResult) {
    const pending = this.pending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    pending.cleanup();
    this.pending.delete(id);
    pending.resolve(result);
  }
}
