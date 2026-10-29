import { z } from 'zod';

export const PROTOCOL_VERSION = 1;
export const packageName = z.string().regex(/^[a-zA-Z][\w]*(\.[\w]+)+$/).max(255);
const point = z.number().int().min(0).max(16384);
export const toolInputs = {
  phone_status: z.object({}).strict(),
  phone_unlock: z.object({}).strict(),
  phone_location: z.object({ timeoutMs: z.number().int().min(1000).max(20000).default(15000) }).strict(),
  phone_ui: z.object({ maxNodes: z.number().int().min(1).max(500).default(300) }).strict(),
  phone_screenshot: z.object({ maxDimension: z.number().int().min(256).max(1440).default(1080) }).strict(),
  phone_open_app: z.object({ packageName }).strict(),
  phone_tap: z.object({ packageName, nodeId: z.string().max(100).optional(), x: point.optional(), y: point.optional() }).strict()
    .refine(v => (v.nodeId !== undefined && v.x === undefined && v.y === undefined) ||
      (v.nodeId === undefined && v.x !== undefined && v.y !== undefined), 'Provide a nodeId or both x and y'),
  phone_swipe: z.object({ packageName, fromX: point, fromY: point, toX: point, toY: point,
    durationMs: z.number().int().min(100).max(2000).default(400) }).strict(),
  phone_input: z.object({ packageName, text: z.string().max(10000), nodeId: z.string().max(100).optional() }).strict(),
  phone_back: z.object({ packageName }).strict(),
} as const;
export type ToolName = keyof typeof toolInputs;
export const toolDescriptions: Record<ToolName, string> = {
  phone_status: 'Report phone connectivity, permissions, pause state, screen lock state, and optional Shizuku availability.',
  phone_unlock: 'Wake and unlock the phone using only credentials configured locally on that phone. Requires separate phone-side opt-in and operation permission. Takes no PIN, password, or pattern. Never automatically retry a failed unlock; biometric-only or unsupported lock screens require the user.',
  phone_location: 'Get a fresh location on demand. Requires phone-side permission; may return approximate location.',
  phone_ui: 'Read the current authorized visible UI. Node IDs expire after another UI read, UI change, or reconnect. Passwords are redacted.',
  phone_screenshot: 'Capture the authorized display using the Android accessibility screenshot API. Secure or unauthorized windows are refused.',
  phone_open_app: 'Open an app explicitly allowed for operations on the phone. Uses optional Shizuku only if the user enabled it.',
  phone_tap: 'Tap a node from the latest UI snapshot or screen coordinates in an authorized foreground app.',
  phone_swipe: 'Swipe in an authorized foreground app. Coordinates are physical screen pixels.',
  phone_input: 'Set text on an editable node or the focused field in an authorized app. Does not submit or press Enter.',
  phone_back: 'Send Android Back from the expected authorized foreground app. Read UI afterward to check the result.',
};
export const resultSchema = z.discriminatedUnion('ok', [
  z.object({ ok: z.literal(true), data: z.unknown() }).strict(),
  z.object({ ok: z.literal(false), error: z.object({ code: z.string().max(80), message: z.string().max(1000),
    outcomeUnknown: z.boolean().optional() }).strict() }).strict(),
]);
export type ToolResult = z.infer<typeof resultSchema>;
export function failure(code: string, message: string, outcomeUnknown = false): ToolResult {
  return { ok: false, error: { code, message, ...(outcomeUnknown ? { outcomeUnknown } : {}) } };
}
export function isTool(name: string): name is ToolName { return Object.hasOwn(toolInputs, name); }
export const isOperation = (name: ToolName) => ['phone_unlock', 'phone_open_app', 'phone_tap', 'phone_swipe', 'phone_input', 'phone_back'].includes(name);
