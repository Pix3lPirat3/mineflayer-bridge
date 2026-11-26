import type { EnvelopeHeader, MessageName, MessagePayloads } from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import { messagePermission } from './devices.ts';
import type { Session } from './session.ts';

/** Routes an in-order session message to the part of the mock that handles it, after the permission check. */
export function dispatchSessionMessage(core: ModCore, s: Session, type: MessageName, header: EnvelopeHeader, payload: unknown): void {
  const permission = messagePermission(type, payload);
  if (permission !== undefined && !core.devices.granted(s, permission)) return core.devices.deny(s, permission, type, header, header.type);
  const p = <N extends MessageName>(_: N): MessagePayloads[N] => payload as MessagePayloads[N];
  // A chat prompt takes the player's next chat line.
  if (type === 'prompt.request' && p('prompt.request').surface === 'chat' && !core.devices.granted(s, 'chat')) return core.devices.deny(s, 'chat', type, header, header.type);
  switch (type) {
    case 'ping':
      if (s.conn !== null) core.sendConn(s.conn, 'pong', { time: p('ping').time });
      return;
    case 'ack':
    case 'pong':
    case 'stream.accept':
    case 'stream.have':
    case 'stream.window':
      return;
    case 'error':
      return core.chat.onCompletionResponse(s, header, undefined);
    case 'command.suggestions':
      return core.chat.onCompletionResponse(s, header, p('command.suggestions'));
    case 'session.close':
      return core.closeSession(s, p('session.close').reason, false);
    case 'world.report':
      s.world = p('world.report').world;
      s.position = p('world.report').position;
      return core.player.refreshAll();
    case 'presence.status':
      s.status = p('presence.status').status;
      return;
    case 'sample.request':
      return core.player.onSampleRequest(s, p('sample.request'));
    case 'obj.snapshot.begin':
      s.snapshot = new Set();
      return;
    case 'obj.snapshot.end':
      return core.store.endSnapshot(s);
    case 'obj.upsert':
      if (s.tx !== undefined) return void s.tx.writes.push({ type: 'upsert', payload: p('obj.upsert') });
      return core.store.upsert(s, p('obj.upsert').writes);
    case 'obj.remove':
      if (s.tx !== undefined) return void s.tx.writes.push({ type: 'remove', payload: p('obj.remove') });
      return core.store.remove(s, p('obj.remove'));
    case 'obj.begin':
      return core.store.beginTx(s, p('obj.begin').tx);
    case 'obj.commit':
      return core.store.commitTx(s, p('obj.commit').tx, header);
    case 'focus.request':
      return core.player.onFocusRequest(s, p('focus.request'));
    case 'obj.edit.result':
      return core.shared.onEditResult(s, header, p('obj.edit.result'));
    case 'shared.subscribe':
      return core.shared.subscribe(s, p('shared.subscribe'));
    case 'shared.op':
      return core.shared.op(s, header, p('shared.op'));
    case 'notify':
      return core.displays.onNotify(s, p('notify'));
    case 'effect.play':
      return core.displays.onEffect(s, p('effect.play'));
    case 'pick.request':
      return core.inputs.onPick(s, header, p('pick.request'));
    case 'query.entities':
      return core.queries.onEntities(s, header, p('query.entities'));
    case 'query.blocks':
      return core.queries.onBlocks(s, header, p('query.blocks'));
    case 'prompt.request':
      return core.inputs.onPrompt(s, header, p('prompt.request'));
    case 'world-menu.request':
      return core.inputs.onWorldMenu(s, header, p('world-menu.request'));
    case 'form.open':
      return core.inputs.onForm(s, header, p('form.open'));
    case 'pick.cancel':
    case 'prompt.cancel':
    case 'form.close':
    case 'world-menu.cancel':
      return core.inputs.onWithdrawn(s, p('pick.cancel').request);
    case 'container.open':
      return core.views.onBotOpen(s, p('container.open').view);
    case 'container.close':
      return core.views.onBotClose(s, p('container.close').view);
    case 'screen.open':
      return core.screens.onBotOpen(s, p('screen.open').screen);
    case 'screen.close':
      return core.screens.onBotClose(s, p('screen.close').screen);
    case 'container.ack':
      return core.views.onAck(s, p('container.ack'));
    case 'chat.print':
      return core.chat.onPrint(s, p('chat.print'));
    case 'invocation.result':
      return core.chat.onInvocationResult(s, p('invocation.result'));
    case 'stream.offer':
      return core.streams.onOffer(s, p('stream.offer'));
    case 'stream.chunk':
      return core.streams.onChunk(s, p('stream.chunk'));
    case 'stream.end':
      return core.streams.onEnd(s, p('stream.end').streamId);
    case 'stream.reject':
      return core.streams.onReject(s, p('stream.reject'));
    default:
      core.sendSessionError(s, 'unsupported', `${type} is not handled by the mock`, header);
  }
}
