// PTT Radio relay for Cloudflare Workers. Same wire protocol as ../server/server.js:
// JSON text frames for control, binary frames for single Opus packets.
//
// All state lives in one Durable Object. It uses the WebSocket Hibernation API so that an idle
// relay (phones connected, nobody talking) costs nothing: the object is evicted from memory and
// rebuilt from each socket's attachment when the next message arrives.

import { DurableObject } from 'cloudflare:workers';

const MAX_TALK_MS = 60_000;   // longest single transmission
const IDLE_MS = 3_000;        // floor is released if the speaker sends no audio for this long
const MAX_NAME = 24;
const MAX_MESSAGE = 4096;
const ECHO_CODE = 'RADIOCHECK'; // built-in channel that plays your transmission back to you
const ECHO_MAX_FRAMES = 750;    // 15 s
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I

const cleanName = s => String(s ?? '').replace(/[\x00-\x1f]/g, '').trim().slice(0, MAX_NAME);
const cleanCode = s => String(s ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16);
const who = c => (c ? { id: c.id, name: c.name } : null);

export default {
  fetch(request, env) {
    return env.RELAY.get(env.RELAY.idFromName('main')).fetch(request);
  },
};

export class Relay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    /** code -> { code, name, echo, members:Set<client>, speaker, since, lastAudio, echoBuf, prefix } */
    this.channels = new Map();
    /** connection id -> { cid, ws, id, name, joined:Set<code>, talking } */
    this.clients = new Map();
    this.watchdog = null;

    // The app's keepalive is answered by the platform without waking (or billing) the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}'));

    // Runs on first start and again after every hibernation: rebuild memory from durable state.
    ctx.blockConcurrencyWhile(async () => {
      for (const [key, name] of await ctx.storage.list({ prefix: 'ch:' })) this.addChannel(key.slice(3), name);
      if (!this.channels.has(ECHO_CODE)) this.addChannel(ECHO_CODE, 'Radio Check');
      this.channels.get(ECHO_CODE).echo = true;

      for (const ws of ctx.getWebSockets()) {
        const a = ws.deserializeAttachment();
        if (!a?.cid) continue;
        const client = { cid: a.cid, ws, id: a.id, name: a.name, joined: new Set(), talking: null };
        this.clients.set(a.cid, client);
        for (const code of a.joined ?? []) {
          const ch = this.channels.get(code);
          if (ch) { ch.members.add(client); client.joined.add(code); }
        }
      }
    });
  }

  // ---- connections --------------------------------------------------------------------------

  async fetch(request) {
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
      return new Response(`PTT relay OK - ${this.clients.size} online, ${this.channels.size} channels\n`, {
        headers: { 'content-type': 'text/plain' },
      });
    }
    const { 0: outside, 1: ws } = new WebSocketPair();
    this.ctx.acceptWebSocket(ws);
    const client = { cid: crypto.randomUUID(), ws, id: null, name: '', joined: new Set(), talking: null };
    this.clients.set(client.cid, client);
    this.remember(client);
    return new Response(null, { status: 101, webSocket: outside });
  }

  /** Save what must survive hibernation on the socket itself. */
  remember(client) {
    client.ws.serializeAttachment({ cid: client.cid, id: client.id, name: client.name, joined: [...client.joined] });
  }

  clientOf(ws) {
    const client = this.clients.get(ws.deserializeAttachment()?.cid);
    if (client) client.ws = ws;
    return client;
  }

  webSocketMessage(ws, message) {
    const client = this.clientOf(ws);
    if (!client) return;
    if (typeof message !== 'string') {
      if (message.byteLength <= MAX_MESSAGE) this.onAudio(client, new Uint8Array(message));
      return;
    }
    if (message.length > MAX_MESSAGE) return;
    let msg;
    try { msg = JSON.parse(message); } catch { return; }
    if (msg && typeof msg === 'object') this.onMessage(client, msg);
  }

  webSocketClose(ws) {
    this.drop(this.clientOf(ws));
  }

  webSocketError(ws) {
    this.drop(this.clientOf(ws));
  }

  drop(client) {
    if (!client || !this.clients.has(client.cid)) return;
    this.clients.delete(client.cid);
    for (const code of [...client.joined]) this.leave(client, this.channels.get(code), false);
  }

  send(client, msg) {
    try { client.ws.send(JSON.stringify(msg)); } catch { /* socket already gone */ }
  }

  broadcast(ch, msg) {
    const text = JSON.stringify(msg);
    for (const m of ch.members) {
      try { m.ws.send(text); } catch { /* socket already gone */ }
    }
  }

  // ---- channels -----------------------------------------------------------------------------

  addChannel(code, name) {
    const bytes = new TextEncoder().encode(code);
    const prefix = new Uint8Array(1 + bytes.length);
    prefix[0] = bytes.length;
    prefix.set(bytes, 1);
    const ch = { code, name, echo: false, members: new Set(), speaker: null, since: 0, lastAudio: 0, echoBuf: [], prefix };
    this.channels.set(code, ch);
    return ch;
  }

  createChannel(code, name) {
    this.ctx.storage.put('ch:' + code, name);
    return this.addChannel(code, name);
  }

  newCode() {
    for (;;) {
      let code = '';
      for (const b of crypto.getRandomValues(new Uint8Array(8))) code += ALPHABET[b % ALPHABET.length];
      if (!this.channels.has(code)) return code;
    }
  }

  sendMembers(ch) {
    this.broadcast(ch, { t: 'members', code: ch.code, members: [...ch.members].map(who) });
  }

  join(client, ch) {
    ch.members.add(client);
    client.joined.add(ch.code);
    this.remember(client);
    this.send(client, { t: 'joined', code: ch.code, name: ch.name });
    this.sendMembers(ch);
    if (ch.speaker) this.send(client, { t: 'floor', code: ch.code, speaker: who(ch.speaker) });
  }

  leave(client, ch, stillConnected = true) {
    if (!ch) return;
    if (ch.speaker === client) this.release(ch);
    ch.members.delete(client);
    client.joined.delete(ch.code);
    if (stillConnected) this.remember(client);
    this.sendMembers(ch);
  }

  // ---- floor control and audio --------------------------------------------------------------

  release(ch) {
    const prev = ch.speaker;
    if (!prev) return;
    ch.speaker = null;
    prev.talking = null;
    this.broadcast(ch, { t: 'floor', code: ch.code, speaker: null });
    if (ch.echo) {
      const frames = ch.echoBuf;
      ch.echoBuf = [];
      if (frames.length) this.playEcho(prev, ch, frames);
    }
  }

  // Radio check: replay the speaker's own transmission back to them at real-time pace.
  playEcho(client, ch, frames) {
    this.send(client, { t: 'floor', code: ch.code, speaker: { id: 'echo', name: 'RADIO CHECK' } });
    let i = 0;
    const timer = setInterval(() => {
      if (i >= frames.length || !this.clients.has(client.cid) || ch.speaker) {
        clearInterval(timer);
        if (!ch.speaker) this.send(client, { t: 'floor', code: ch.code, speaker: null });
        return;
      }
      try { client.ws.send(this.frame(ch, frames[i++])); } catch { i = frames.length; }
    }, 20);
  }

  frame(ch, data) {
    const out = new Uint8Array(ch.prefix.length + data.length);
    out.set(ch.prefix);
    out.set(data, ch.prefix.length);
    return out;
  }

  onAudio(client, data) {
    const ch = client.talking && this.channels.get(client.talking);
    if (!ch || ch.speaker !== client || data.length === 0) return;
    ch.lastAudio = Date.now();
    if (ch.echo) {
      if (ch.echoBuf.length < ECHO_MAX_FRAMES) ch.echoBuf.push(data.slice());
      return;
    }
    const frame = this.frame(ch, data);
    for (const m of ch.members) {
      if (m === client) continue;
      try { m.ws.send(frame); } catch { /* socket already gone */ }
    }
  }

  // Frees floors held by silent or over-long speakers. A timer keeps the object in memory, so it
  // only runs while somebody holds a floor.
  startWatchdog() {
    if (this.watchdog) return;
    this.watchdog = setInterval(() => {
      const now = Date.now();
      let busy = false;
      for (const ch of this.channels.values()) {
        if (!ch.speaker) continue;
        if (now - ch.lastAudio > IDLE_MS || now - ch.since > MAX_TALK_MS) this.release(ch);
        else busy = true;
      }
      if (!busy) {
        clearInterval(this.watchdog);
        this.watchdog = null;
      }
    }, 1000);
  }

  // ---- control messages ---------------------------------------------------------------------

  onMessage(client, msg) {
    if (msg.t === 'hello') {
      const id = String(msg.id ?? '').slice(0, 64);
      const name = cleanName(msg.name);
      if (!id || !name) return this.send(client, { t: 'error', msg: 'Callsign required' });
      if (!client.id) {
        // The same phone reconnecting replaces its old, possibly dead, connection.
        for (const old of [...this.clients.values()]) {
          if (old !== client && old.id === id) {
            try { old.ws.close(4000, 'replaced'); } catch { /* already closed */ }
            this.drop(old);
          }
        }
        client.id = id;
      }
      client.name = name;
      this.remember(client);
      for (const code of client.joined) this.sendMembers(this.channels.get(code));
      return;
    }
    if (!client.id) return this.send(client, { t: 'error', msg: 'Say hello first' });

    switch (msg.t) {
      case 'create': {
        const name = cleanName(msg.name);
        if (!name) return this.send(client, { t: 'error', msg: 'Channel name required' });
        this.join(client, this.createChannel(this.newCode(), name));
        break;
      }
      case 'join': {
        const code = cleanCode(msg.code);
        let ch = this.channels.get(code);
        if (!ch) {
          // A phone re-joining a channel it already knows (it sends the name) restores it;
          // a hand-typed unknown code is an error.
          const name = cleanName(msg.name);
          if (!name || code.length < 8) return this.send(client, { t: 'nochannel', code });
          ch = this.createChannel(code, name);
        }
        if (client.joined.size >= 50 && !client.joined.has(code)) {
          return this.send(client, { t: 'error', msg: 'Too many channels' });
        }
        this.join(client, ch);
        break;
      }
      case 'leave': {
        const ch = this.channels.get(cleanCode(msg.code));
        if (ch && ch.members.has(client)) this.leave(client, ch);
        break;
      }
      case 'ptt': {
        const ch = this.channels.get(cleanCode(msg.code));
        if (!ch || !ch.members.has(client)) return;
        if (msg.down) {
          if (ch.speaker && ch.speaker !== client) {
            return this.send(client, { t: 'denied', code: ch.code, by: ch.speaker.name });
          }
          if (client.talking && client.talking !== ch.code) this.release(this.channels.get(client.talking));
          ch.speaker = client;
          ch.since = ch.lastAudio = Date.now();
          ch.echoBuf = [];
          client.talking = ch.code;
          this.broadcast(ch, { t: 'floor', code: ch.code, speaker: who(client) });
          this.startWatchdog();
        } else if (ch.speaker === client) {
          this.release(ch);
        }
        break;
      }
    }
  }
}
