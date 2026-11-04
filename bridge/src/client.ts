import type { Config } from './config.js';
import { failure, isOperation, resultSchema, type ToolName, type ToolResult } from './protocol.js';

/** Translate both legacy HTTP errors and current structured Bridge results. */
export async function callBridge(baseUrl: string, config: Config, tool: ToolName, args: unknown, signal?: AbortSignal): Promise<ToolResult> {
  if (signal?.aborted) return failure('CANCELLED', 'Harness cancelled before the request was sent');
  try {
    const timeout = AbortSignal.timeout(config.requestTimeoutMs + 3000);
    const response = await fetch(`${baseUrl}/api/call`, { method: 'POST',
      headers: { authorization: `Bearer ${config.harnessToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ tool, args }), signal: signal ? AbortSignal.any([signal, timeout]) : timeout });
    const payload: unknown = await response.json().catch(() => null);
    const parsed = resultSchema.safeParse(payload);
    if (response.ok) return parsed.success ? parsed.data
      : failure('BRIDGE_PROTOCOL_ERROR', 'Bridge returned an invalid result; update and restart the Bridge', isOperation(tool));
    if (parsed.success && !parsed.data.ok) return parsed.data;
    const legacyError = payload !== null && typeof payload === 'object' && 'error' in payload ? payload.error : undefined;
    if (response.status === 400 && legacyError === 'Unknown tool') {
      return failure('TOOL_NOT_SUPPORTED_BY_BRIDGE', 'The running Bridge does not support this tool; update and restart the Bridge');
    }
    if (response.status === 401) return failure('BRIDGE_AUTH_FAILED', 'Bridge rejected the Harness credential');
    if (response.status === 403) return failure('BRIDGE_ACCESS_DENIED', 'Bridge rejected the request Host, Origin, or access policy');
    if (response.status === 400) return failure('INVALID_ARGUMENT', 'Bridge rejected the tool request');
    if (response.status === 404) return failure('BRIDGE_ENDPOINT_NOT_FOUND', 'Bridge API endpoint was not found; check the address and running Bridge version');
    if (response.status === 429) return failure('BUSY', 'Bridge is rate limiting requests');
    return failure('BRIDGE_UNAVAILABLE', `Bridge returned HTTP ${response.status}`, isOperation(tool));
  } catch {
    return failure(signal?.aborted ? 'CANCELLED' : 'BRIDGE_UNAVAILABLE',
      'Bridge request did not complete; do not automatically repeat an action with an unknown result', isOperation(tool));
  }
}
