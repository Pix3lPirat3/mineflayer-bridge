/**
 * Remote interop driver: connects to a core over wss with a device
 * credential created by `mineflayer-bridge pair`, declares one box, prints `READY`, and exits on `EXIT`.
 */
import { requireArgs, serveCommands, startBridge } from './fake-bot.ts';

const [endpoint, credentials] = requireArgs(2, 'remote.ts <wss-endpoint> <credentials.json>') as [string, string];

const api = await startBridge('Remote', { endpoint, credentials, name: 'remote', reconnect: false });
api.layer('remote').box('paired', { x: 3, y: 64, z: 3 }, 1, { world: 'global' });
await serveCommands(api);
