/**
 * Cross-language interop driver. Started by the Java test
 * `NodeInteropTest` with the endpoint file of a real BridgeCore; drives the real plugin with a fake bot.
 *
 * Protocol with the parent over stdio: prints `READY` once the shapes are declared, then reads commands
 * line by line: `MOVE` moves the box and prints `MOVED`, `EXIT` closes the bridge and exits.
 */
import { requireArgs, serveCommands, startBridge } from './fake-bot.ts';

const [endpointFile, credentials] = requireArgs(2, 'declare.ts <endpoint.json> <credentials.json>') as [string, string];

const api = await startBridge('Interop', { endpointFile, credentials, name: 'interop' });
api.style('red', { color: '#ff0000', fill: 0.25, depth: 'xray' });
const layer = api.layer('route');
layer.box('target', { x: 1, y: 64, z: 1 }, 1, { style: 'red' });
layer.label('hello', { x: 0, y: 66, z: 0 }, 'Hello from Node');

await serveCommands(api, {
  MOVE: () => {
    layer.box('target', { x: 5, y: 64, z: 5 }, 1, { style: 'red' });
    console.log('MOVED');
  },
});
