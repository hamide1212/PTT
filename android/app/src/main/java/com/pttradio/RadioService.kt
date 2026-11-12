package com.pttradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the radio on the air while the app is in the background or the screen is off,
 * so friends can reach you at any time. Runs until "Go off air" is tapped in the notification.
 */
class RadioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Radio.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Radio status", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        // Without these the CPU and Wi-Fi sleep with the screen and incoming calls are missed.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pttradio:link").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pttradio:link").apply { acquire() }

        Radio.goOnline()
        scope.launch {
            Radio.state.map { status(it) }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(it))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Radio.goOffline()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    private fun status(s: RadioState): String {
        val speaking = s.live.entries.firstOrNull { it.value.speaker != null && it.value.speaker?.id != s.myId }
        return when {
            !s.connected -> "No link — reconnecting…"
            s.transmitting -> "Transmitting"
            speaking != null -> {
                val net = s.channels.firstOrNull { it.code == speaking.key }?.name ?: speaking.key
                "${speaking.value.speaker?.name} on $net"
            }
            s.channels.isEmpty() -> "On air — no channels yet"
            else -> "On air — monitoring ${s.channels.size} channel${if (s.channels.size == 1) "" else "s"}"
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle("PTT Radio")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Go off air", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "radio"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.pttradio.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RadioService::class.java))
        }
    }
}
