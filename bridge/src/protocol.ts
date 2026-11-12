import { z } from 'zod';

export const PROTOCOL_VERSION = 1;
export const BRIDGE_VERSION = '0.3.0';
export const packageName = z.string().regex(/^[a-zA-Z][\w]*(\.[\w]+)+$/).max(255);
const point = z.number().int().min(0).max(16384);
export const toolInputs = {
  phone_status: z.object({}).strict(),
  phone_device_info: z.object({}).strict(),
  phone_apps: z.object({ includeSystem: z.boolean().default(false) }).strict(),
  phone_notifications: z.object({ maxItems: z.number().int().min(1).max(100).default(50) }).strict(),
  phone_clipboard: z.object({}).strict(),
  phone_set_clipboard: z.object({ text: z.string().max(10000) }).strict(),
  phone_media: z.object({ kind: z.enum(['images', 'videos', 'all']).default('all'), limit: z.number().int().min(1).max(100).default(50) }).strict(),
  phone_unlock: z.object({}).strict(),
  phone_lock: z.object({}).strict(),
  phone_location: z.object({ timeoutMs: z.number().int().min(1000).max(20000).default(15000) }).strict(),
  phone_ui: z.object({ maxNodes: z.number().int().min(1).max(500).default(300) }).strict(),
  phone_screenshot: z.object({ maxDimension: z.number().int().min(256).max(1440).default(1080) }).strict(),
  phone_open_app: z.object({ packageName }).strict(),
  phone_tap: z.object({ packageName, nodeId: z.string().max(100).optional(), x: point.optional(), y: point.optional(),
    // Keep omitted durations off the wire so older APKs can still accept taps.
    durationMs: z.number().int().min(50).max(2000).optional() }).strict()
    .refine(v => (v.nodeId !== undefined && v.x === undefined && v.y === undefined) ||
      (v.nodeId === undefined && v.x !== undefined && v.y !== undefined), 'Provide a nodeId or both x and y'),
  phone_swipe: z.object({ packageName, fromX: point, fromY: point, toX: point, toY: point,
    durationMs: z.number().int().min(100).max(2000).default(400) }).strict(),
  phone_input: z.object({ packageName, text: z.string().max(10000), nodeId: z.string().max(100).optional() }).strict(),
  phone_back: z.object({ packageName }).strict(),
  phone_key: z.object({ key: z.enum(['HOME', 'BACK', 'ENTER', 'TAB']), packageName: packageName.optional() }).strict(),
} as const;
export type ToolName = keyof typeof toolInputs;
export const toolDescriptions: Record<ToolName, string> = {
  phone_status: 'Report phone connectivity, permissions, pause state, screen lock state, Shizuku availability, and Bridge version, protocol, and supportedTools. Bridge information remains available when the phone is offline.',
  phone_device_info: 'Read device model, Android version, battery, display, locale, and time zone information.',
  phone_apps: 'List installed launchable applications and their versions. System packages are hidden unless requested.',
  phone_notifications: 'Read active notifications after the user enables the notification access service and authorizes their apps.',
  phone_clipboard: 'Read the current clipboard text when Android allows clipboard access.',
  phone_set_clipboard: 'Replace the clipboard text. Android may show its normal clipboard privacy notice.',
  phone_media: 'List recent image and video metadata from shared media after the corresponding media permission is granted.',
  phone_unlock: 'Wake and unlock the phone using only credentials configured locally on that phone. Requires separate phone-side opt-in and operation permission. Takes no PIN, password, or pattern. Never automatically retry a failed unlock; biometric-only or unsupported lock screens require the user.',
  phone_lock: 'Request an explicit screen lock through accessibility. Requires its own phone-side tool permission and operation authorization for every visible window. Does not toggle power or wake an already locked phone. accepted confirms dispatch; use phone_status.screen to observe the result. Never replay an uncertain operation.',
  phone_location: 'Get a fresh location on demand. Requires phone-side permission; may return approximate location.',
  phone_ui: 'Read the current authorized visible UI. Node IDs expire after another UI read, UI change, or reconnect. Passwords are redacted.',
  phone_screenshot: 'Capture the authorized display. Returns scaleX=width/displayWidth and scaleY=height/displayHeight; divide screenshot coordinates by these scales for physical screen coordinates. Secure or unauthorized windows are refused.',
  phone_open_app: 'Open an app explicitly allowed for operations on the phone. Uses optional Shizuku only if the user enabled it.',
  phone_tap: 'Tap or long-press a node from the latest UI snapshot or physical screen coordinates in an authorized foreground app. durationMs defaults to 50 (50–2000); a node duration of at least 500 requests its long-click action.',
  phone_swipe: 'Swipe in an authorized foreground app. Coordinates are physical screen pixels.',
  phone_input: 'Set text on an editable node or the focused field in an authorized app. Does not submit or press Enter.',
  phone_back: 'Send Android Back from the expected authorized foreground app. Read UI afterward to check the result.',
  phone_key: 'Send HOME, BACK, ENTER, or TAB in the authorized foreground UI. Optional packageName asserts the current foreground app; omit it to act on an authorized system overlay. Every visible window still requires operation authorization and accessibility must be enabled. ENTER may submit a form. Without Shizuku, ENTER requires a non-password editable focus and TAB uses forward input focus. Read UI afterward.',
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
export const isOperation = (name: ToolName) => ['phone_unlock', 'phone_lock', 'phone_open_app', 'phone_tap', 'phone_swipe', 'phone_input', 'phone_back', 'phone_key', 'phone_set_clipboard'].includes(name);
export const bridgeInfo = () => ({ version: BRIDGE_VERSION, protocol: PROTOCOL_VERSION, supportedTools: Object.keys(toolInputs) });
