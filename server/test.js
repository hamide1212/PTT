'use strict';
// Smoke test: two clients, create/join, floor control, audio relay, radio check.
// Run the server first (npm start), then: npm test
const WebSocket = require('ws');
const URL = process.env.URL || 'ws://localhost:8080';

function client(id, name) {
  const ws = new WebSocket(URL);
  const c = { ws, msgs: [], audio: [] };
  ws.on('message', (d, bin) => (bin ? c.audio.push(d) : c.msgs.push(JSON.parse(d.toString()))));
  c.send = m => ws.send(JSON.stringify(m));
  c.ready = new Promise(r => ws.on('open', () => { c.send({ t: 'hello', id, name }); r(); }));
  c.last = t => [...c.msgs].reverse().find(m => m.t === t);
  return c;
}
// SLOW=5 stretches every wait, for relays reached over the internet instead of localhost.
const SLOW = +process.env.SLOW || 1;
const sleep = ms => new Promise(r => setTimeout(r, ms * SLOW));
let failed = 0;
function check(label, ok) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`);
  if (!ok) failed++;
}

(async () => {
  const a = client('test-a', 'ALPHA'), b = client('test-b', 'BRAVO');
  await Promise.all([a.ready, b.ready]);

  a.send({ t: 'create', name: 'Test Net' });
  await sleep(150);
  const code = a.last('joined')?.code;
  check('create returns an 8-char code', /^[A-Z2-9]{8}$/.test(code || ''));

  b.send({ t: 'join', code: code.toLowerCase().slice(0, 4) + '-' + code.slice(4) });
  await sleep(150);
  check('join by formatted code', b.last('joined')?.code === code);
  check('both see 2 members', a.last('members')?.members.length === 2);

  b.send({ t: 'join', code: 'ZZZZZZZZ' });
  await sleep(100);
  check('unknown code rejected', b.last('nochannel')?.code === 'ZZZZZZZZ');

  a.send({ t: 'ptt', code, down: true });
  await sleep(100);
  check('speaker gets floor', a.last('floor')?.speaker?.name === 'ALPHA');
  check('listener sees floor', b.last('floor')?.speaker?.name === 'ALPHA');

  b.send({ t: 'ptt', code, down: true });
  await sleep(100);
  check('second speaker denied', b.last('denied')?.by === 'ALPHA');

  a.ws.send(Buffer.from([1, 2, 3, 4]));
  b.ws.send(Buffer.from([9, 9])); // not the speaker: must be dropped
  await sleep(100);
  const f = b.audio[0];
  check('audio relayed with channel prefix',
    !!f && f[0] === 8 && f.subarray(1, 9).toString() === code && f.subarray(9).equals(Buffer.from([1, 2, 3, 4])));
  check('speaker hears nothing back', a.audio.length === 0);

  a.send({ t: 'ptt', code, down: false });
  await sleep(100);
  check('floor released', b.last('floor')?.speaker === null);

  a.send({ t: 'join', code: 'RADIOCHECK' });
  await sleep(100);
  a.send({ t: 'ptt', code: 'RADIOCHECK', down: true });
  await sleep(50);
  for (let i = 0; i < 5; i++) a.ws.send(Buffer.from([i]));
  await sleep(50);
  a.send({ t: 'ptt', code: 'RADIOCHECK', down: false });
  await sleep(400);
  check('radio check echoes 5 frames', a.audio.length === 5);

  b.send({ t: 'ptt', code, down: true });
  await sleep(4500); // IDLE_MS (3 s) + up to 1 s of watchdog granularity
  check('silent speaker loses floor', a.last('floor')?.speaker === null);

  a.send({ t: 'leave', code });
  await sleep(100);
  check('leave updates members', b.last('members')?.members.length === 1);

  a.ws.close(); b.ws.close();
  console.log(failed ? `\n${failed} FAILED` : '\nAll passed');
  process.exit(failed ? 1 : 0);
})();
