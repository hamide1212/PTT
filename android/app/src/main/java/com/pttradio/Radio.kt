package com.pttradio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Member(val id: String, val name: String)

/** What is happening on a channel right now, as reported by the relay. */
data class Live(val members: List<Member> = emptyList(), val speaker: Member? = null)

data class RadioState(
    val callsign: String = "",
    val server: String = "",
    val myId: String = "",
    val connected: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val live: Map<String, Live> = emptyMap(),
    val selected: String? = null,
    val transmitting: Boolean = false,
)

/**
 * The radio itself: one connection to the relay, shared by the UI and the foreground service.
 * All state changes happen on the main thread; only audio packets are handled off it.
 */
object Radio {
    private const val TAG = "PTTRadio"

    val state = MutableStateFlow(RadioState())
    /** Short one-off messages for the user ("Channel busy", "Joined ALPHA"). */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private var ws: WebSocket? = null
    private var online = false
    private var retryMs = 1000L
    private val reconnect = Runnable { connect() }

    // Free cloud hosts put a relay to sleep unless real messages arrive (WebSocket pings don't count).
    private const val KEEPALIVE_MS = 60_000L
    private val keepalive = object : Runnable {
        override fun run() {
            if (send(JSONObject().put("t", "ping"))) main.postDelayed(this, KEEPALIVE_MS)
        }
    }
    private val pendingJoins = LinkedHashSet<String>()

    private var wantTalk = false
    private var talkCode: String? = null
    @Volatile private var talker: Talker? = null // also read on the network thread
    private val listeners = HashMap<String, Listener>() // guarded by itself
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }

    fun init(ctx: Context) {
        if (::store.isInitialized) return
        store = Store(ctx.applicationContext)
        val channels = store.channels
        state.value = RadioState(
            callsign = store.callsign,
            server = store.server,
            myId = store.id,
            channels = channels,
            selected = store.selected?.takeIf { s -> channels.any { it.code == s } } ?: channels.firstOrNull()?.code,
        )
    }

    // ---- connection -------------------------------------------------------------------------

    fun goOnline() {
        online = true
        connect()
    }

    fun goOffline() {
        online = false
        main.removeCallbacks(reconnect)
        ws?.close(1000, null)
        dropLink()
    }

    private fun connect() {
        main.removeCallbacks(reconnect)
        val s = state.value
        if (!online || ws != null || s.callsign.isBlank()) return
        val request = try {
            Request.Builder().url(s.server).build()
        } catch (e: IllegalArgumentException) {
            events.tryEmit("Bad server address")
            return
        }
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onMain(webSocket) { opened() }

            override fun onMessage(webSocket: WebSocket, text: String) = onMain(webSocket) {
                runCatching { handle(JSONObject(text)) }.onFailure { Log.w(TAG, "bad message $text", it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = audioIn(bytes)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onMain(webSocket) { lost() }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(TAG, "link down: ${t.message}")
                onMain(webSocket) { lost() }
            }
        })
    }

    /** Run on the main thread, and only if [socket] is still the current connection. */
    private fun onMain(socket: WebSocket, block: () -> Unit) {
        main.post { if (socket === ws) block() }
    }

    private fun opened() {
        retryMs = 1000
        val s = state.value
        state.update { it.copy(connected = true) }
        send(JSONObject().put("t", "hello").put("id", s.myId).put("name", s.callsign))
        s.channels.forEach { send(JSONObject().put("t", "join").put("code", it.code).put("name", it.name)) }
        pendingJoins.forEach { send(JSONObject().put("t", "join").put("code", it)) }
        pendingJoins.clear()
        main.removeCallbacks(keepalive)
        main.postDelayed(keepalive, KEEPALIVE_MS)
    }

    private fun lost() {
        dropLink()
        if (online) {
            main.postDelayed(reconnect, retryMs)
            retryMs = (retryMs * 2).coerceAtMost(10_000)
        }
    }

    private fun dropLink() {
        ws = null
        main.removeCallbacks(keepalive)
        stopTalking()
        wantTalk = false
        synchronized(listeners) {
            listeners.values.forEach { it.finish() }
            listeners.clear()
        }
        state.update { it.copy(connected = false, live = emptyMap()) }
    }

    private fun send(msg: JSONObject): Boolean = ws?.send(msg.toString()) ?: false

    // ---- messages from the relay ------------------------------------------------------------

    private fun handle(m: JSONObject) {
        when (m.getString("t")) {
            "joined" -> {
                val ch = Channel(m.getString("code"), m.getString("name"))
                val isNew = state.value.channels.none { it.code == ch.code }
                state.update { s ->
                    val list = if (isNew) s.channels + ch else s.channels.map { if (it.code == ch.code) ch else it }
                    s.copy(channels = list, selected = if (isNew) ch.code else s.selected ?: ch.code)
                }
                persist()
                if (isNew) events.tryEmit("Joined ${ch.name}")
            }

            "members" -> {
                val a = m.getJSONArray("members")
                val members = List(a.length()) { i -> a.getJSONObject(i).let { Member(it.getString("id"), it.getString("name")) } }
                updateLive(m.getString("code")) { it.copy(members = members) }
            }

            "floor" -> {
                val code = m.getString("code")
                val speaker = m.optJSONObject("speaker")?.let { Member(it.getString("id"), it.getString("name")) }
                val previous = state.value.live[code]?.speaker
                updateLive(code) { it.copy(speaker = speaker) }
                val me = state.value.myId
                when {
                    speaker?.id == me -> {
                        if (wantTalk && talkCode == code) startTalking() else sendPtt(code, false)
                    }
                    else -> {
                        if (talkCode == code && talker != null) { // the relay took the floor away
                            stopTalking()
                            wantTalk = false
                            events.tryEmit("Transmission ended")
                        }
                        if (speaker == null) {
                            synchronized(listeners) { listeners.remove(code) }?.finish()
                            if (previous != null && previous.id != me) beep(ToneGenerator.TONE_PROP_ACK, 120)
                        }
                    }
                }
            }

            "denied" -> {
                wantTalk = false
                beep(ToneGenerator.TONE_PROP_NACK, 200)
                events.tryEmit("Channel busy: ${m.optString("by")} is transmitting")
            }

            "nochannel" -> events.tryEmit("No channel with code ${formatCode(m.optString("code"))}")
            "error" -> events.tryEmit(m.optString("msg"))
        }
    }

    private fun updateLive(code: String, change: (Live) -> Live) {
        state.update { it.copy(live = it.live + (code to change(it.live[code] ?: Live()))) }
    }

    private fun persist() {
        store.channels = state.value.channels
        store.selected = state.value.selected
    }

    // ---- audio ------------------------------------------------------------------------------

    /** Relay audio frame: [code length][code][Opus packet]. Called on OkHttp's reader thread. */
    private fun audioIn(bytes: ByteString) {
        if (bytes.size < 2 || talker != null) return // half duplex: deaf while transmitting
        val n = bytes[0].toInt()
        if (bytes.size <= 1 + n) return
        val code = bytes.substring(1, 1 + n).utf8()
        val packet = bytes.substring(1 + n).toByteArray()
        synchronized(listeners) { listeners.getOrPut(code) { Listener() } }.offer(packet)
    }

    private fun startTalking() {
        if (talker != null) return
        val socket = ws ?: return
        beep(ToneGenerator.TONE_PROP_BEEP, 60)
        talker = Talker { packet -> socket.send(packet.toByteString()) }
        state.update { it.copy(transmitting = true) }
    }

    private fun stopTalking() {
        talker?.stop()
        talker = null
        state.update { it.copy(transmitting = false) }
    }

    private fun beep(toneType: Int, ms: Int) {
        runCatching { tone?.startTone(toneType, ms) }
    }

    // ---- actions from the UI ----------------------------------------------------------------

    fun pttDown() {
        val s = state.value
        val code = s.selected ?: return
        if (!s.connected) {
            events.tryEmit("No link to relay")
            return
        }
        wantTalk = true
        talkCode = code
        sendPtt(code, true)
    }

    fun pttUp() {
        if (!wantTalk) return
        wantTalk = false
        stopTalking()
        talkCode?.let { sendPtt(it, false) }
    }

    private fun sendPtt(code: String, down: Boolean) {
        send(JSONObject().put("t", "ptt").put("code", code).put("down", down))
    }

    fun createChannel(name: String) {
        if (!send(JSONObject().put("t", "create").put("name", name.trim()))) events.tryEmit("No link to relay")
    }

    fun join(rawCode: String) {
        val code = rawCode.uppercase().filter { it.isLetterOrDigit() }
        if (code.isEmpty()) return
        if (state.value.channels.any { it.code == code }) {
            select(code)
            return
        }
        if (!send(JSONObject().put("t", "join").put("code", code))) pendingJoins += code
    }

    fun leave(code: String) {
        if (talkCode == code) pttUp()
        send(JSONObject().put("t", "leave").put("code", code))
        synchronized(listeners) { listeners.remove(code) }?.finish()
        state.update { s ->
            val list = s.channels.filter { it.code != code }
            s.copy(
                channels = list,
                live = s.live - code,
                selected = if (s.selected == code) list.firstOrNull()?.code else s.selected,
            )
        }
        persist()
    }

    fun select(code: String) {
        if (state.value.transmitting) return
        state.update { it.copy(selected = code) }
        store.selected = code
    }

    fun setCallsign(name: String) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        store.callsign = clean
        state.update { it.copy(callsign = clean) }
        if (!send(JSONObject().put("t", "hello").put("id", state.value.myId).put("name", clean))) connect()
    }

    fun setServer(url: String) {
        val clean = url.trim()
        if (clean.isEmpty() || clean == state.value.server) return
        store.server = clean
        state.update { it.copy(server = clean) }
        ws?.cancel()
        dropLink()
        connect()
    }

    /** ABCDEFGH -> ABCD-EFGH, the way codes are shown and shared. */
    fun formatCode(code: String): String = if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code
}
