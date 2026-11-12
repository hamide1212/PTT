package com.pttradio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0B0F0A)
private val Panel = Color(0xFF141B11)
private val Line = Color(0xFF2A3622)
private val Olive = Color(0xFF4F5E2C)
private val Green = Color(0xFF8FD14F)
private val Amber = Color(0xFFFFB020)
private val Red = Color(0xFFFF4D3D)
private val Ink = Color(0xFFD7E0C8)
private val Dim = Color(0xFF7D8A6C)
private val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Radio.init(applicationContext)
        handleInvite(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Green, onPrimary = Bg, background = Bg, surface = Panel,
                    onSurface = Ink, onSurfaceVariant = Dim, outline = Line, error = Red,
                    surfaceContainerHigh = Panel, inverseSurface = Ink, inverseOnSurface = Bg,
                )
            ) { App() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    /** pttradio://join?code=XXXXXXXX */
    private fun handleInvite(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "pttradio") uri.getQueryParameter("code")?.let(Radio::join)
    }
}

private fun Context.granted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun App() {
    val s by Radio.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    LaunchedEffect(Unit) {
        Radio.events.collect { msg ->
            scope.launch {
                snack.currentSnackbarData?.dismiss()
                snack.showSnackbar(msg)
            }
        }
    }

    val enlisted = s.callsign.isNotBlank()
    LaunchedEffect(enlisted) {
        if (!enlisted) return@LaunchedEffect
        RadioService.start(ctx)
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot(ctx::granted)
        if (wanted.isNotEmpty()) askPermissions.launch(wanted.toTypedArray())
    }

    if (!enlisted) {
        Onboarding()
        return
    }

    var dialog by remember { mutableStateOf<Dialog?>(null) }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = { Header(s, onSettings = { dialog = Dialog.Settings }) },
        bottomBar = {
            PttPanel(s, onNeedMic = { askPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) })
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetButton("+ NEW CHANNEL", Modifier.weight(1f)) { dialog = Dialog.Create }
                    NetButton("JOIN WITH CODE", Modifier.weight(1f)) { dialog = Dialog.Join }
                }
            }
            if (s.channels.isEmpty()) {
                item {
                    Text(
                        "NO CHANNELS YET\n\nCreate a channel and share its code with your friends, " +
                            "or join one with a code you were sent.\n\n" +
                            "Tip: join RADIOCHECK to hear your own transmission played back.",
                        Modifier.fillMaxWidth().padding(24.dp),
                        color = Dim, fontFamily = Mono, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp,
                    )
                }
            }
            items(s.channels, key = { it.code }) { ch ->
                ChannelCard(
                    ch, s.live[ch.code] ?: Live(), selected = ch.code == s.selected, myId = s.myId, online = s.connected,
                    onSelect = { Radio.select(ch.code) },
                    onShare = { share(ctx, ch) },
                    onLeave = { dialog = Dialog.Leave(ch) },
                )
            }
        }
    }

    when (val d = dialog) {
        Dialog.Create -> Prompt(
            title = "NEW CHANNEL", label = "Channel name", confirm = "CREATE",
            onDismiss = { dialog = null },
        ) { Radio.createChannel(it); dialog = null }

        Dialog.Join -> Prompt(
            title = "JOIN CHANNEL", label = "Channel code", confirm = "JOIN", allCaps = true,
            onDismiss = { dialog = null },
        ) { Radio.join(it); dialog = null }

        Dialog.Settings -> SettingsDialog(s) { dialog = null }

        is Dialog.Leave -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("LEAVE ${d.channel.name.uppercase()}?", fontFamily = Mono, fontSize = 16.sp) },
            text = { Text("You will stop hearing this channel. You can rejoin with its code.", color = Dim) },
            confirmButton = { TextButton({ Radio.leave(d.channel.code); dialog = null }) { Text("LEAVE", color = Red, fontFamily = Mono) } },
            dismissButton = { TextButton({ dialog = null }) { Text("CANCEL", color = Dim, fontFamily = Mono) } },
        )

        null -> {}
    }
}

private sealed interface Dialog {
    data object Create : Dialog
    data object Join : Dialog
    data object Settings : Dialog
    data class Leave(val channel: Channel) : Dialog
}

private fun share(ctx: Context, ch: Channel) {
    val text = "Join my channel \"${ch.name}\" on PTT Radio.\n" +
        "Code: ${Radio.formatCode(ch.code)}\n" +
        "pttradio://join?code=${ch.code}"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Share channel"))
}

@Composable
private fun Onboarding() {
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PTT RADIO", color = Green, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 4.sp)
        Spacer(Modifier.height(8.dp))
        Text("Pick a callsign. This is how your friends will see you on the net.", color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(24) }, singleLine = true,
            label = { Text("Callsign") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { Radio.setCallsign(name) }, enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(6.dp),
        ) { Text("GO ON AIR", fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
    }
}

@Composable
private fun Header(s: RadioState, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("CALLSIGN", color = Dim, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Text(
                s.callsign.uppercase(), color = Ink, fontFamily = Mono, fontWeight = FontWeight.Bold,
                fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val link = if (s.connected) Green else Red
        Row(
            Modifier.border(1.dp, link.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(link))
            Spacer(Modifier.width(6.dp))
            Text(if (s.connected) "LINK UP" else "NO LINK", color = link, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Dim) }
    }
}

@Composable
private fun NetButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick, modifier.height(46.dp), shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Green),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) { Text(label, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun ChannelCard(
    ch: Channel, live: Live, selected: Boolean, myId: String, online: Boolean,
    onSelect: () -> Unit, onShare: () -> Unit, onLeave: () -> Unit,
) {
    val speaker = live.speaker
    val accent = when {
        speaker?.id == myId -> Red
        speaker != null -> Amber
        selected -> Green
        else -> Line
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel)
            .border(if (selected) 2.dp else 1.dp, accent, RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect).padding(start = 14.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    ch.name.uppercase(), color = Ink, fontFamily = Mono, fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Radio.formatCode(ch.code) + if (online) "  ·  ${live.members.size} ON NET" else "",
                    color = Dim, fontFamily = Mono, fontSize = 12.sp,
                )
            }
            IconButton(onShare) { Icon(Icons.Default.Share, "Share channel code", tint = Green) }
            IconButton(onLeave) { Icon(Icons.Default.Close, "Leave channel", tint = Dim) }
        }
        if (speaker != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (speaker.id == myId) "● YOU ARE TRANSMITTING" else "● ${speaker.name.uppercase()} TRANSMITTING",
                color = accent, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        } else if (selected && live.members.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                live.members.filter { it.id != myId }.joinToString("  ") { it.name.uppercase() },
                Modifier.padding(end = 14.dp),
                color = Green.copy(alpha = 0.8f), fontFamily = Mono, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PttPanel(s: RadioState, onNeedMic: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val channel = s.channels.firstOrNull { it.code == s.selected }
    val speaker = s.live[s.selected]?.speaker?.takeIf { it.id != s.myId }

    val (color, status) = when {
        channel == null -> Line to "SELECT A CHANNEL"
        !s.connected -> Line to "NO LINK"
        s.transmitting -> Red to "TRANSMITTING"
        speaker != null -> Amber to "${speaker.name.uppercase()} ON AIR"
        else -> Olive to "HOLD TO TALK"
    }
    val pulse by rememberInfiniteTransition(label = "tx").animateFloat(
        0.25f, 0.9f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "tx",
    )

    Column(
        Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(top = 14.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            channel?.name?.uppercase() ?: "—", color = Ink, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, letterSpacing = 2.sp, maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.size(176.dp)
                .border(4.dp, color.copy(alpha = if (s.transmitting) pulse else 0.9f), CircleShape)
                .padding(10.dp).clip(CircleShape)
                .background(if (s.transmitting) Red else Bg)
                .border(2.dp, color, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (!ctx.granted(Manifest.permission.RECORD_AUDIO)) {
                            onNeedMic()
                        } else {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            Radio.pttDown()
                            tryAwaitRelease()
                            Radio.pttUp()
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (s.transmitting) "TX" else "PTT",
                color = if (s.transmitting) Bg else color.takeIf { it != Line && it != Olive } ?: Green,
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 3.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            status, color = if (color == Line || color == Olive) Dim else color,
            fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Prompt(
    title: String, label: String, confirm: String, allCaps: Boolean = false,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = Mono, fontSize = 16.sp, letterSpacing = 2.sp) },
        text = {
            OutlinedTextField(
                value, { value = if (allCaps) it.uppercase().take(20) else it.take(24) },
                singleLine = true, label = { Text(label) },
                keyboardOptions = KeyboardOptions(
                    capitalization = if (allCaps) KeyboardCapitalization.Characters else KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                ),
            )
        },
        confirmButton = {
            TextButton({ onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm, fontFamily = Mono, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onDismiss) { Text("CANCEL", color = Dim, fontFamily = Mono) } },
    )
}

@Composable
private fun SettingsDialog(s: RadioState, onDismiss: () -> Unit) {
    var callsign by remember { mutableStateOf(s.callsign) }
    var server by remember { mutableStateOf(s.server) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SETTINGS", fontFamily = Mono, fontSize = 16.sp, letterSpacing = 2.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    callsign, { callsign = it.take(24) }, singleLine = true, label = { Text("Callsign") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedTextField(
                    server, { server = it.trim() }, singleLine = true, label = { Text("Relay server") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                Text("Everyone on a channel must use the same relay server.", color = Dim, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                { Radio.setCallsign(callsign); Radio.setServer(server); onDismiss() },
                enabled = callsign.isNotBlank() && server.isNotBlank(),
            ) { Text("SAVE", fontFamily = Mono, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onDismiss) { Text("CANCEL", color = Dim, fontFamily = Mono) } },
    )
}
