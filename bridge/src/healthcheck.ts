import { loadConfig, localBaseUrl } from './config.js';

try {
  const config = loadConfig(process.env.PHONE_BRIDGE_CONFIG ?? '/data/bridge.config.json');
  const response = await fetch(`${localBaseUrl(config)}/health`, { signal: AbortSignal.timeout(3000) });
  if (!response.ok || !(await response.json() as { ok?: boolean }).ok) process.exitCode = 1;
} catch { process.exitCode = 1; }
