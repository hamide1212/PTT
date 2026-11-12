# PTT Radio

Push-to-talk radio for Android. Create a channel, share its code with friends, hold the button to talk.
Everyone on the channel hears you instantly through their loudspeaker, even with the app in the background.

- `server/` — relay (Node.js + WebSocket): channels, who is on the net, who holds the floor, audio fan-out.
- `android/` — the app (Kotlin, Jetpack Compose). Audio is Opus, 16 kHz, 24 kbit/s.

## Run the relay

```bash
cd server
npm install
npm start        # listens on :8080 (PORT=xxxx to change)
npm test         # smoke test against a running relay
```

Channels are saved to `server/channels.json`.

## Build the app

```bash
cd android
./gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
```

Every phone must point at the same relay: **Settings (gear) → Relay server**, e.g. `ws://192.168.1.20:8080`.
The built-in default is `Store.DEFAULT_SERVER` in `android/app/src/main/java/com/pttradio/Store.kt`.

## Using it

1. Pick a callsign.
2. **+ NEW CHANNEL**, then the share icon to send the code (`ABCD-EFGH`) to friends. They use **JOIN WITH CODE**.
3. Tap a channel to select it, hold **PTT** to talk. You hear every channel you have joined.
4. Join the code `RADIOCHECK` to hear your own transmission played back.

One person talks at a time per channel; a transmission is cut after 60 s.

## Protocol

JSON text frames: `hello`, `create`, `join`, `leave`, `ptt` from the client;
`joined`, `members`, `floor`, `denied`, `nochannel`, `error` from the relay.
Binary frames are single Opus packets: bare from the speaker, prefixed with `[code length][code]` to listeners.
