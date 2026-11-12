'use strict';
// PTT Radio relay.
// Text frames are JSON control messages; binary frames are Opus packets (one 20 ms frame each).
// One speaker per channel at a time ("the floor"), like a real half-duplex radio net.

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = +process.env.PORT || 8080;
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'channels.json');
const MAX_TALK_MS = 60_000;   // longest single transmission
const IDLE_MS = 3_000;        // floor is released if the speaker sends no audio for this long
const MAX_NAME = 24;
const ECHO_CODE = 'RADIOCHECK'; // built-in channel that plays your transmission back to you
const ECHO_MAX_FRAMES = 750;    // 15 s
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I

/** code -> { code, name, members:Set<client>, speaker, since, lastAudio, prefix, echo, echoBuf } */
const channels = new Map();
/** id -> client */
const clients = new Map();

function addChannel(code, name, echo = false) {
  const ch = {
    code, name, echo,
    members: new Set(), speaker: null, since: 0, lastAudio: 0, echoBuf: [],
    prefix: Buffer.concat([Buffer.from([code.length]), Buffer.from(code, 'ascii')]),
  };
  channels.set(code, ch);
  return ch;
}

function load() {
  try {
    for (const c of JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'))) addChannel(c.code, c.name);
  } catch { /* first run */ }
  if (!channels.has(ECHO_CODE)) addChannel(ECHO_CODE, 'Radio Check', true);
  else channels.get(ECHO_CODE).echo = true;
}

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const list = [...channels.values()].filter(c => !c.echo).map(c => ({ code: c.code, name: c.name }));
    fs.writeFile(DATA_FILE, JSON.stringify(list), err => err && console.error('save failed', err.message));
  }, 500);
}

const cleanName = s => String(s ?? '').replace(/[\x00-\x1f]/g, '').trim().slice(0, MAX_NAME);
const cleanCode = s => String(s ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16);

function newCode() {
  for (;;) {
    const bytes = crypto.randomBytes(8);
    let code = '';
    for (const b of bytes) code += ALPHABET[b % ALPHABET.length];
    if (!channels.has(code)) return code;
  }
}

function send(client, msg) {
  if (client.ws.readyState === 1) client.ws.send(JSON.stringify(msg));
}
function broadcast(ch, msg) {
  const text = JSON.stringify(msg);
  for (const m of ch.members) if (m.ws.readyState === 1) m.ws.send(text);
}
const who = c => (c ? { id: c.id, name: c.name } : null);
function sendMembers(ch) {
  broadcast(ch, { t: 'members', code: ch.code, members: [...ch.members].map(who) });
}

function join(client, ch) {
  ch.members.add(client);
  client.joined.add(ch.code);
  send(client, { t: 'joined', code: ch.code, name: ch.name });
  sendMembers(ch);
  if (ch.speaker) send(client, { t: 'floor', code: ch.code, speaker: who(ch.speaker) });
}

function leave(client, ch) {
  if (ch.speaker === client) release(ch);
  ch.members.delete(client);
  client.joined.delete(ch.code);
  sendMembers(ch);
}

function release(ch) {
  const prev = ch.speaker;
  if (!prev) return;
  ch.speaker = null;
  prev.talking = null;
  broadcast(ch, { t: 'floor', code: ch.code, speaker: null });
  if (ch.echo) {
    const frames = ch.echoBuf;
    ch.echoBuf = [];
    if (frames.length) playEcho(prev, ch, frames);
  }
}

// Radio check: replay the speaker's own transmission back to them at real-time pace.
function playEcho(client, ch, frames) {
  send(client, { t: 'floor', code: ch.code, speaker: { id: 'echo', name: 'RADIO CHECK' } });
  let i = 0;
  const timer = setInterval(() => {
    if (i >= frames.length || client.ws.readyState !== 1 || ch.speaker) {
      clearInterval(timer);
      if (!ch.speaker) send(client, { t: 'floor', code: ch.code, speaker: null });
      return;
    }
    client.ws.send(Buffer.concat([ch.prefix, frames[i++]]), { binary: true });
  }, 20);
}

function onAudio(client, data) {
  const ch = client.talking && channels.get(client.talking);
  if (!ch || ch.speaker !== client || data.length === 0) return;
  ch.lastAudio = Date.now();
  if (ch.echo) {
    if (ch.echoBuf.length < ECHO_MAX_FRAMES) ch.echoBuf.push(Buffer.from(data));
    return;
  }
  const frame = Buffer.concat([ch.prefix, data]);
  for (const m of ch.members) {
    // Skip the speaker, and skip listeners whose socket is backed up (stale audio is worse than a gap).
    if (m !== client && m.ws.readyState === 1 && m.ws.bufferedAmount < 64 * 1024) {
      m.ws.send(frame, { binary: true });
    }
  }
}

function onMessage(client, msg) {
  if (msg.t === 'hello') {
    const id = String(msg.id ?? '').slice(0, 64);
    const name = cleanName(msg.name);
    if (!id || !name) return send(client, { t: 'error', msg: 'Callsign required' });
    if (!client.id) {
      const old = clients.get(id);
      if (old && old !== client) old.ws.close(4000, 'replaced');
      client.id = id;
      clients.set(id, client);
    }
    client.name = name;
    for (const code of client.joined) sendMembers(channels.get(code));
    return;
  }
  if (!client.id) return send(client, { t: 'error', msg: 'Say hello first' });

  switch (msg.t) {
    case 'create': {
      const name = cleanName(msg.name);
      if (!name) return send(client, { t: 'error', msg: 'Channel name required' });
      const ch = addChannel(newCode(), name);
      save();
      join(client, ch);
      break;
    }
    case 'join': {
      const code = cleanCode(msg.code);
      let ch = channels.get(code);
      if (!ch) {
        // A client re-joining a channel it already knows (it sends the name) restores it if the
        // server lost its data; a hand-typed unknown code is an error.
        const name = cleanName(msg.name);
        if (!name || code.length < 8) return send(client, { t: 'nochannel', code });
        ch = addChannel(code, name);
        save();
      }
      if (client.joined.size >= 50 && !client.joined.has(code)) {
        return send(client, { t: 'error', msg: 'Too many channels' });
      }
      join(client, ch);
      break;
    }
    case 'leave': {
      const ch = channels.get(cleanCode(msg.code));
      if (ch && ch.members.has(client)) leave(client, ch);
      break;
    }
    case 'ptt': {
      const ch = channels.get(cleanCode(msg.code));
      if (!ch || !ch.members.has(client)) return;
      if (msg.down) {
        if (ch.speaker && ch.speaker !== client) {
          return send(client, { t: 'denied', code: ch.code, by: ch.speaker.name });
        }
        if (client.talking && client.talking !== ch.code) release(channels.get(client.talking));
        ch.speaker = client;
        ch.since = ch.lastAudio = Date.now();
        ch.echoBuf = [];
        client.talking = ch.code;
        broadcast(ch, { t: 'floor', code: ch.code, speaker: who(client) });
      } else if (ch.speaker === client) {
        release(ch);
      }
      break;
    }
  }
}

load();

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain' });
  res.end(`PTT relay OK - ${clients.size} online, ${channels.size} channels\n`);
});
const wss = new WebSocketServer({ server, maxPayload: 4096 });

wss.on('connection', ws => {
  const client = { ws, id: null, name: '', joined: new Set(), talking: null, alive: true };
  ws.client = client;
  ws.on('pong', () => { client.alive = true; });
  ws.on('message', (data, isBinary) => {
    client.alive = true;
    if (isBinary) return onAudio(client, data);
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }
    if (msg && typeof msg === 'object') onMessage(client, msg);
  });
  ws.on('close', () => {
    for (const code of [...client.joined]) leave(client, channels.get(code));
    if (client.id && clients.get(client.id) === client) clients.delete(client.id);
  });
  ws.on('error', () => {});
});

// Watchdog: free floors held by silent or over-long speakers, and drop dead sockets.
setInterval(() => {
  const now = Date.now();
  for (const ch of channels.values()) {
    if (ch.speaker && (now - ch.lastAudio > IDLE_MS || now - ch.since > MAX_TALK_MS)) release(ch);
  }
}, 1000);
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.client.alive) { ws.terminate(); continue; }
    ws.client.alive = false;
    ws.ping();
  }
}, 25_000);

server.listen(PORT, () => console.log(`PTT relay listening on :${PORT}`));
