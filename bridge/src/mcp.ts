import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { toolDescriptions, toolInputs, type ToolName, type ToolResult, isOperation } from './protocol.js';

export type Caller = (tool: ToolName, args: unknown, signal?: AbortSignal) => Promise<ToolResult>;
export function createMcpServer(call: Caller) {
  const server = new McpServer({ name: 'android-phone-bridge', version: '0.1.0' });
  for (const tool of Object.keys(toolInputs) as ToolName[]) {
    server.registerTool(tool, {
      description: toolDescriptions[tool], inputSchema: toolInputs[tool],
      annotations: { readOnlyHint: !isOperation(tool), destructiveHint: isOperation(tool),
        idempotentHint: !isOperation(tool), openWorldHint: true },
    }, async (args: unknown, extra: { signal: AbortSignal }) => {
      const result = await call(tool, args, extra.signal);
      if (tool === 'phone_screenshot' && result.ok) {
        const data = result.data as { base64?: unknown; mimeType?: unknown } | null;
        if (data && typeof data.base64 === 'string' && data.mimeType === 'image/png') {
          const { base64: _base64, ...metadata } = data;
          return { content: [{ type: 'image' as const, data: data.base64, mimeType: 'image/png' },
            { type: 'text' as const, text: JSON.stringify({ ok: true, data: metadata }) }], isError: false };
        }
      }
      return { content: [{ type: 'text' as const, text: JSON.stringify(result) }], isError: !result.ok };
    });
  }
  return server;
}
