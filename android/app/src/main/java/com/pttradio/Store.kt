package com.pttradio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Channel(val code: String, val name: String)

/** Everything the radio remembers between launches. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)

    /** Stable per-install identity, so the relay can tell two people with the same callsign apart. */
    val id: String
        get() = p.getString("id", null)
            ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }

    var callsign: String
        get() = p.getString("callsign", "") ?: ""
        set(v) = p.edit().putString("callsign", v).apply()

    var server: String
        get() = p.getString("server", null)?.takeUnless { it in RETIRED_SERVERS } ?: defaultServer()
        set(v) = p.edit().putString("server", v).apply()

    var selected: String?
        get() = p.getString("selected", null)
        set(v) = p.edit().putString("selected", v).apply()

    var channels: List<Channel>
        get() = runCatching {
            val a = JSONArray(p.getString("channels", "[]"))
            List(a.length()) { i -> a.getJSONObject(i).let { Channel(it.getString("code"), it.getString("name")) } }
        }.getOrDefault(emptyList())
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("code", it.code).put("name", it.name)) }
            p.edit().putString("channels", a.toString()).apply()
        }

    companion object {
        /** Relay address used until one is set in Settings. Change this to your deployed relay. */
        const val DEFAULT_SERVER = "wss://hamide.tailac2f38.ts.net"

        /** Earlier relay addresses: phones that saved one of these move to the default on update. */
        private val RETIRED_SERVERS = setOf("wss://ptt-relay.ptt-relay-cloudflare.workers.dev", "ws://127.0.0.1:8080", "ws://192.168.11.114:8080")

        fun defaultServer(): String = DEFAULT_SERVER
    }
}
