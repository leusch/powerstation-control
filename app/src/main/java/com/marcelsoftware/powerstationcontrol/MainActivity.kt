package com.marcelsoftware.powerstationcontrol

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var ble: BleClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleClient(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent {
            ZoraTheme { Surface(Modifier.fillMaxSize(), color = Zora.Canvas) { App(ble) } }
        }
    }

    override fun onStart() { super.onStart(); ble.resume() }
    override fun onDestroy() { super.onDestroy(); if (isFinishing) ble.disconnect() }
}

private val PERMS = if (Build.VERSION.SDK_INT >= 31)
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Single-colour Material icon (res/drawable/ic_*.xml), tinted with the given colour. */
@Composable
fun ZIcon(res: Int, size: Dp = 20.dp, tint: Color = Zora.TextPrimary, modifier: Modifier = Modifier, description: String? = null) =
    Icon(painterResource(res), contentDescription = description, modifier = modifier.size(size), tint = tint)

// ---------------------------------------------------------------- formatting helpers

fun fmtSeconds(sec: Int): String = when {
    sec < 60 -> "$sec s"
    sec % 60 == 0 -> "${sec / 60} min"
    else -> "${sec / 60} min ${sec % 60} s"
}

@Composable
fun fmtMinutes(min: Int): String = when {
    min <= 0 -> "–"
    min >= 60 * 48 -> stringResource(R.string.more_than_days, min / 60 / 24)
    min >= 60 -> "${min / 60} h ${min % 60} min"
    else -> "$min min"
}

// ---------------------------------------------------------------- root

@Composable
fun App(ble: BleClient) {
    val conn by ble.conn.collectAsStateWithLifecycle()
    val devices by ble.devices.collectAsStateWithLifecycle()
    val status by ble.status.collectAsStateWithLifecycle()
    val settings by ble.settingsRegs.collectAsStateWithLifecycle()
    val error by ble.error.collectAsStateWithLifecycle()
    val message by ble.lastWrite.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }

    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) ble.start()
    }
    LaunchedEffect(Unit) { if (ble.hasPermissions()) ble.start() else askPerms.launch(PERMS) }
    BackHandler(settingsOpen) { settingsOpen = false }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (settingsOpen) TextButton(onClick = { settingsOpen = false }) {
                ZIcon(R.drawable.ic_arrow_back, 20.dp, Zora.TextPrimary); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.back), fontSize = 16.sp, color = Zora.TextPrimary) }
            else Text(stringResource(R.string.title_main), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (!settingsOpen) IconButton(onClick = { settingsOpen = true }) { ZIcon(R.drawable.ic_settings, 26.dp, Zora.TextPrimary, description = stringResource(R.string.settings)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                settingsOpen -> SettingsScreen(ble, conn, status, settings, message)
                conn == Conn.Connected && status != null -> MainScreen(ble, status!!, message)
                conn == Conn.Connected -> Waiting(stringResource(R.string.reading_data))
                else -> ConnectScreen(ble, conn, devices, error, askPerms = { askPerms.launch(PERMS) })
            }
        }
    }
}

// ---------------------------------------------------------------- connection

@Composable
fun Waiting(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(text, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun ConnectScreen(ble: BleClient, conn: Conn, devices: List<Found>, error: String?, askPerms: () -> Unit) {
    when (conn) {
        Conn.Connecting -> {
            Waiting(stringResource(R.string.connecting_to, ble.savedName ?: stringResource(R.string.device_fallback)))
            error?.let { Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 13.sp) }
            OutlinedButton(onClick = { ble.disconnect(manual = true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
        Conn.Scanning -> {
            var showAll by remember { mutableStateOf(false) }
            val shown = if (showAll) devices else devices.filter { it.likely }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                Text(stringResource(if (shown.isEmpty()) R.string.searching else R.string.found_tap), fontSize = 15.sp)
            }
            shown.forEach { d ->
                ZoraCard(Modifier.fillMaxWidth(), onClick = { ble.connect(d.address, d.name) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(d.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.signal_fmt, signalText(d.rssi)), fontSize = 12.sp)
                    }
                }
            }
            if (shown.isEmpty()) Text(stringResource(R.string.not_found_hint), fontSize = 13.sp)
            TextButton(onClick = { showAll = !showAll }) { Text(stringResource(if (showAll) R.string.show_stations_only else R.string.show_all_devices)) }
        }
        else -> {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ZIcon(R.drawable.ic_battery_full, 64.dp, Zora.AccentSoft)
                Text(stringResource(R.string.not_connected), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                error?.let { Text(it, textAlign = TextAlign.Center, fontSize = 14.sp, color = MaterialTheme.colorScheme.error) }
                if (ble.savedAddress != null) {
                    Button(onClick = { if (ble.hasPermissions()) ble.start() else askPerms() }, Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.connect_to, ble.savedName ?: stringResource(R.string.device_fallback_short)), fontSize = 16.sp)
                    }
                    OutlinedButton(onClick = { ble.forgetAndSearch() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.search_other)) }
                } else {
                    Button(onClick = { if (ble.hasPermissions()) ble.start() else askPerms() }, Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.search_station), fontSize = 16.sp)
                    }
                }
                Text(stringResource(R.string.tip_single_connection),
                    fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun signalText(rssi: Int) = stringResource(when {
    rssi > -60 -> R.string.signal_very_good
    rssi > -75 -> R.string.signal_good
    else -> R.string.signal_weak
})

// ---------------------------------------------------------------- main screen

@Composable
fun MainScreen(ble: BleClient, s: Status, message: String?) {
    // Battery + power flow
    val pct = s.batteryPct ?: 0
    ZoraCard(Modifier.fillMaxWidth(), gradient = Brush.linearGradient(listOf(Zora.TintStrong, Zora.Sky.copy(alpha = 0.55f), Zora.Rose.copy(alpha = 0.35f)))) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.batteryPct?.let { "$it %" } ?: "–", fontSize = 58.sp, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { (pct / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(8.dp),
                color = if (pct < 20) Zora.Danger else Zora.Accent, trackColor = Zora.Surface.copy(alpha = 0.7f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) { ZIcon(R.drawable.ic_arrow_downward, 14.dp, Zora.TextSecondary); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.input_label), fontSize = 12.5.sp, color = Zora.TextSecondary) }
                    Text("${s.totalInW} W", fontSize = 22.sp) }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.output_label), fontSize = 12.5.sp, color = Zora.TextSecondary); Spacer(Modifier.width(4.dp)); ZIcon(R.drawable.ic_arrow_upward, 14.dp, Zora.TextSecondary) }
                    Text("${s.totalOutW} W", fontSize = 22.sp) }
            }
            val charging = s.totalInW > s.totalOutW && s.timeToFullMin in 1..6000
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZIcon(if (charging) R.drawable.ic_battery_charging_full else R.drawable.ic_hourglass_empty, 18.dp, Zora.AccentStrong)
                Spacer(Modifier.width(8.dp))
                Text(if (charging) stringResource(R.string.full_in, fmtMinutes(s.timeToFullMin)) else stringResource(R.string.runtime_left, fmtMinutes(s.remainingMin)), fontSize = 14.sp)
            }
        }
    }

    // Outputs
    val offText = stringResource(R.string.state_off)
    Text(stringResource(R.string.section_outputs), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton(s.acOn, R.drawable.ic_electrical_services, "AC", if (s.acOn) "${s.acOutW} W" else offText, Modifier.weight(1f)) {
            ble.setOutput("AC", Protocol.BIT_AC, !s.acOn) }
        BigButton(s.dcOn, R.drawable.ic_directions_car, "DC", if (s.dcOn) "${s.dcOutW} W" else offText, Modifier.weight(1f)) {
            ble.setOutput("DC", Protocol.BIT_DC, !s.dcOn) }
        BigButton(s.usbOn, R.drawable.ic_usb, "USB", if (s.usbOn) "${s.usbOutW} W" else offText, Modifier.weight(1f)) {
            ble.setOutput("USB", Protocol.BIT_USB, !s.usbOn) }
    }

    // Light
    Text(stringResource(R.string.section_light), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val mode = s.registers.getOrNull(79) ?: 0
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Off: no symbol. On: em dash. SOS: warning sign. Flashing: three middle dots.
        listOf(Triple(0, null, offText), Triple(1, "\u2014", stringResource(R.string.light_on)), Triple(2, R.drawable.ic_warning, stringResource(R.string.light_sos)), Triple(3, "\u00B7\u00B7\u00B7", stringResource(R.string.light_flash))).forEach { (m, symbol, label) ->
            BigButton(mode == m, symbol as? Int, label, "", Modifier.weight(1f), height = 92.dp, glyph = symbol as? String) { ble.setLightMode(m) }
        }
    }

    message?.let { Text(it, Modifier.fillMaxWidth(), fontSize = 13.sp, textAlign = TextAlign.Center) }
}

@Composable
/** Big tile button. Shows a Material icon, a plain text glyph (e.g. "—"), or just the label when neither is given. */
fun BigButton(selected: Boolean, icon: Int?, title: String, subtitle: String, modifier: Modifier,
              height: androidx.compose.ui.unit.Dp = 104.dp, glyph: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(Zora.RadiusLg), modifier = modifier.height(height),
        color = if (selected) Zora.TintStrong else Zora.Surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Zora.Accent else Zora.Border),
    ) {
        Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val ink = if (selected) Zora.AccentStrong else Zora.TextPrimary
            // Fixed-height symbol slot, so the label sits at the same height in every button (even without a symbol).
            Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                if (icon != null) ZIcon(icon, 32.dp, ink)
                else if (glyph != null) Text(glyph, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ink)
            }
            Spacer(Modifier.height(2.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Zora.AccentStrong else Zora.TextPrimary)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.5.sp, color = if (selected) Zora.AccentStrong else Zora.TextSecondary)
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(ble: BleClient, conn: Conn, status: Status?, settings: List<Int>, message: String?) {
    val dev by ble.devMode.collectAsStateWithLifecycle()
    Text(stringResource(R.string.settings_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

    val timeout = settings.getOrNull(25)
    val upper = settings.getOrNull(27)
    val lower = settings.getOrNull(26)
    if (conn == Conn.Connected && timeout != null && upper != null && lower != null) {
        SettingsCard(stringResource(R.string.settings_display)) {
            SettingSlider(stringResource(R.string.display_off_after), timeout.toFloat(), 10f..1800f, 10f, { fmtSeconds(it.toInt()) }) { ble.setScreenTimeout(it.toInt()) }
        }
        SettingsCard(stringResource(R.string.settings_battery)) {
            SettingSlider(stringResource(R.string.stop_charging_at), upper / 10f, 10f..100f, 1f, { "${it.toInt()} %" }) { ble.setChargeMax(it.toInt()) }
            SettingSlider(stringResource(R.string.stop_discharging_at), lower / 10f, 0f..50f, 1f, { "${it.toInt()} %" }) { ble.setDischargeMin(it.toInt()) }
        }
        message?.let { Text(it, Modifier.fillMaxWidth(), fontSize = 13.sp, textAlign = TextAlign.Center) }
    }

    SettingsCard(stringResource(R.string.settings_connection)) {
        Text(when (conn) {
            Conn.Connected -> stringResource(R.string.connected_to, ble.savedName ?: stringResource(R.string.device_fallback))
            Conn.Connecting -> stringResource(R.string.connecting_short)
            Conn.Scanning -> stringResource(R.string.searching_short)
            else -> stringResource(R.string.not_connected) }, fontSize = 15.sp)
        if (conn == Conn.Connected) OutlinedButton(onClick = { ble.disconnect(manual = true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.disconnect)) }
        OutlinedButton(onClick = { ble.forgetAndSearch() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_other)) }
    }

    SettingsCard(stringResource(R.string.settings_dev_card)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.dev_options), fontSize = 16.sp); Switch(checked = dev, onCheckedChange = { ble.setDevMode(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Zora.Accent, checkedThumbColor = Zora.Surface, uncheckedTrackColor = Zora.TintStrong, uncheckedBorderColor = Zora.TextTertiary))
        }
        if (!dev) Text(stringResource(R.string.dev_hint), fontSize = 12.sp)
    }
    if (dev) DevTools(ble, status, settings)
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ZoraCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Zora.AccentStrong)
            content()
        }
    }
}

/** Slider that follows the value reported by the unit and writes once the finger is lifted. */
@Composable
fun SettingSlider(label: String, deviceValue: Float, range: ClosedFloatingPointRange<Float>, step: Float,
                  fmt: (Float) -> String, onCommit: (Float) -> Unit) {
    var sel by remember(deviceValue) { mutableFloatStateOf(deviceValue.coerceIn(range.start, range.endInclusive)) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 15.sp); Text(fmt(sel), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Zora.AccentStrong)
        }
        Slider(value = sel, valueRange = range,
            onValueChange = { sel = ((it / step).roundToInt() * step).coerceIn(range.start, range.endInclusive) },
            onValueChangeFinished = { onCommit(sel) },
            colors = SliderDefaults.colors(thumbColor = Zora.Accent, activeTrackColor = Zora.Accent, inactiveTrackColor = Zora.TintStrong))
    }
}

// ---------------------------------------------------------------- developer tools

@Composable
fun DevTools(ble: BleClient, status: Status?, settings: List<Int>) {
    val changes by ble.changes.collectAsStateWithLifecycle()
    val log by ble.log.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var note by remember { mutableStateOf("") }
    var find by remember { mutableStateOf("") }

    SettingsCard("Developer: notes and refresh") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(note, { note = it }, Modifier.weight(1f), singleLine = true, label = { Text("Note (what did you do?)") })
            Button(onClick = { ble.note(note); note = "" }) { Text("Add") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ble.pollNow() }) { Text("Refresh now") }
            OutlinedButton(onClick = { ble.clearChanges() }) { Text("Clear log") }
        }
    }

    SettingsCard("Developer: find a register") {
        OutlinedTextField(find, { find = it }, Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("A value you can see somewhere") })
        val target = find.replace(',', '.').toDoubleOrNull()
        if (target != null) {
            fun hits(regs: List<Int>, name: String) = regs.withIndex().flatMap { (i, v) ->
                listOf(1.0 to "1:1", 10.0 to "value ÷ 10", 100.0 to "value ÷ 100", 0.1 to "value × 10")
                    .filter { (k, _) -> v != 0 && Math.abs(v - target * k) < 0.5 }.map { (_, how) -> "$name[$i] = $v  ($how)" }
            }
            val all = hits(status?.registers ?: emptyList(), "status") + hits(settings, "settings")
            if (all.isEmpty()) Text("No register matches.", fontSize = 12.sp)
            all.forEach { Text(it, fontSize = 12.sp) }
        }
    }

    SettingsCard("Developer: changes (${changes.size})") {
        if (changes.isEmpty()) Text("Nothing yet. Changes appear as soon as a register changes.", fontSize = 12.sp)
        changes.takeLast(60).reversed().forEach { Text(it, fontSize = 11.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clip.setText(AnnotatedString(ble.report())) }) { Text("Copy report") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ble.report()) }, "Share report"))
            }) { Text("Share") }
        }
    }

    SettingsCard("Developer: raw registers") {
        Text("status: " + (status?.registers?.withIndex()?.filter { it.value != 0 }?.joinToString { "${it.index}=${it.value}" } ?: "–"), fontSize = 11.sp)
        Text("settings: " + settings.withIndex().filter { it.value != 0 }.joinToString { "${it.index}=${it.value}" }, fontSize = 11.sp)
        log.takeLast(8).forEach { Text(it, fontSize = 11.sp) }
    }
}
