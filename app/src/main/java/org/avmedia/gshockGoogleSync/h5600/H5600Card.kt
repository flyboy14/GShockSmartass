package org.avmedia.gshockGoogleSync.h5600

import AppSwitch
import AppText
import AppTextLarge
import android.app.AlertDialog
import android.app.TimePickerDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.avmedia.gshockGoogleSync.ui.common.AppButton
import org.avmedia.gshockGoogleSync.ui.common.AppChoiceButton
import org.avmedia.gshockGoogleSync.ui.common.AppCard
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.model.SoundMode
import org.avmedia.gshockapi.model.WatchFaceItem


/** Custom tab content for the DW-H5600: two cards, watch face first, then sound & notifications. */
@Composable
fun H5600Card(vm: H5600ViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    if (!s.supported) return
    Column {
        WatchFaceCard(vm)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
        // Only with a sun face: the face-2 item Sun, or Auto (steps / sun).
        if (s.dynamicFace || s.watchFace == WatchFaceItem.SUNRISE_SUNSET) {
            SunWeatherCard(vm)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
        }
        SoundNotificationsCard(vm)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
        FindPhoneCard()
    }
}

/** Find Phone: ring sound (system picker), volume, and a 5-second test. Phone-side only. */
@Composable
private fun FindPhoneCard() {
    val context = LocalContext.current
    // The sound's name comes from a content-provider query: load it off the main thread.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var title by remember { mutableStateOf("…") }
    fun loadTitle() = scope.launch {
        title = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            org.avmedia.gshockGoogleSync.ui.actions.FindPhoneSettings.soundTitle(context)
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { loadTitle() }
    var volume by remember { mutableStateOf(org.avmedia.gshockGoogleSync.ui.actions.FindPhoneSettings.volumePct(context).toFloat()) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked: android.net.Uri? = result.data?.let {
            androidx.core.content.IntentCompat.getParcelableExtra(it, android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
        }
        // "Default" in the picker (or nothing picked) = the phone's default alarm sound.
        val isDefault = picked == null || picked == android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        org.avmedia.gshockGoogleSync.ui.actions.FindPhoneSettings.setSoundUri(context, if (isDefault) null else picked)
        loadTitle()
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            CardTitle("Find Phone", "Pressing Find Phone on the watch rings this phone; pressing back on the " +
                "watch stops it. It plays on the alarm channel, so it rings even in silent mode. Lift the phone to stop.")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(text = "Sound: $title", modifier = Modifier.weight(1f))
                org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(
                    text = "Choose",
                    onClick = {
                        val current = org.avmedia.gshockGoogleSync.ui.actions.FindPhoneSettings.soundUri(context)
                            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        picker.launch(
                            android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                                    android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)
                                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Find Phone sound")
                                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
                                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                            },
                        )
                    },
                )
            }
            AppText(text = "Volume: ${volume.toInt()} %", modifier = Modifier.padding(top = 8.dp))
            androidx.compose.material3.Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = {
                    org.avmedia.gshockGoogleSync.ui.actions.FindPhoneSettings.setVolumePct(context, volume.toInt())
                },
                valueRange = 10f..100f,
                steps = 8, // 10 % steps
            )
            var vibrate by remember { mutableStateOf(org.avmedia.gshockGoogleSync.ui.actions.FindPhoneSettings.vibrate(context)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(text = "Vibrate too")
                AppSwitch(checked = vibrate, onCheckedChange = {
                    vibrate = it
                    org.avmedia.gshockGoogleSync.ui.actions.FindPhoneSettings.setVibrate(context, it)
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(
                    text = "Test 5 s",
                    onClick = {
                        org.avmedia.gshockGoogleSync.ui.actions.FindPhoneSettings.setVolumePct(context, volume.toInt())
                        org.avmedia.gshockGoogleSync.ui.actions.PhoneFinder.preview(context)
                    },
                )
                org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(
                    text = "Stop",
                    onClick = { org.avmedia.gshockGoogleSync.ui.actions.PhoneFinder.stop(context) },
                )
            }
        }
    }
}

/** Watch face: Sun data / Steps, dynamic sunset-step toggle, weather instead of sunrise, loc/time. */
@Composable
private fun WatchFaceCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    // HR on the face needs all-day monitoring: ask before turning it on, and before turning it off
    // when HR leaves the face. The face changes either way; Cancel / tapping outside changes nothing.
    var hrAsk by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<HrAsk?>(null) }
    // item null = Auto (Steps by day, Sun data around sunset; see SunFace).
    val current: WatchFaceItem? = if (s.dynamicFace) null else s.watchFace
    val onFaceItem: (WatchFaceItem?) -> Unit = { item ->
        val unchanged = if (item == null) s.dynamicFace else !s.dynamicFace && item == s.watchFace
        val wasHr = !s.dynamicFace && s.watchFace in H5600ViewModel.HR_ITEMS
        val isHr = item in H5600ViewModel.HR_ITEMS
        val allDay = s.hrMonitor == org.avmedia.gshockapi.model.HeartRateMonitor.ALL_DAY
        hrAsk = when {
            unchanged -> null
            isHr && !wasHr && !allDay -> HrAsk(item, turnOn = true)
            !isHr && wasHr && (allDay || s.hrMonitorCode == null) -> HrAsk(item, turnOn = false)
            else -> null
        }
        if (hrAsk == null && !unchanged) vm.selectFace(item, null)
    }
    hrAsk?.let { ask ->
        val off = vm.hrModeWhenOff()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { hrAsk = null },
            title = {
                androidx.compose.material3.Text(
                    if (ask.turnOn) "Turn on all-day heart-rate monitoring?" else "Turn off all-day heart-rate monitoring?",
                )
            },
            text = {
                androidx.compose.material3.Text(
                    if (ask.turnOn) "The heart-rate item shows live readings only with all-day monitoring. " +
                        "The watch's heart-rate monitor will be set to All day (higher battery use)."
                    else "Heart rate is leaving the watch face. The monitor goes back to " +
                        "${hrModeLabel(off)} (less battery use).",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    hrAsk = null
                    vm.selectFace(ask.item, if (ask.turnOn) org.avmedia.gshockapi.model.HeartRateMonitor.ALL_DAY else off)
                }) { androidx.compose.material3.Text(if (ask.turnOn) "Turn on" else "Turn off") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    hrAsk = null
                    vm.selectFace(ask.item, null)
                }) { androidx.compose.material3.Text(if (ask.turnOn) "Keep off" else "Keep on") }
            },
        )
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            CardTitle("Watch face", "Face 1 shows the weekday; Face 2 shows the item chosen below.<br><br>" +
                "Auto: Steps all day, Sun data from the set time before sunset until ${SunFace.TRAIL_MIN} min after " +
                "(− / + change the lead time). The phone switches the face exactly at both edges.")
            // Watch face item
            // Face 1 = simple face with the weekday; face 2 = customisable face showing the item below.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (s.faceNumber == null) org.avmedia.gshockGoogleSync.ui.common.LoadingSpinner()
                else {
                    AppChoiceButton(text = "Face 1 (weekday)", selected = s.faceNumber == 1,
                        onClick = { vm.setFaceNumber(1) })
                    AppChoiceButton(text = "Face 2 (custom)", selected = s.faceNumber == 2,
                        onClick = { vm.setFaceNumber(2) })
                }
            }
            val faceLabels = listOf<Pair<WatchFaceItem?, String>>(
                null to "Auto: Steps/Sun",
                WatchFaceItem.SUNRISE_SUNSET to "Sun", WatchFaceItem.STEPS to "Steps", WatchFaceItem.MOON to "Moon",
                WatchFaceItem.HEART_RATE to "Heart rate", WatchFaceItem.SLEEP to "Sleep",
            )
            // The chosen item is the highlighted button; only an item the app has no button for is named.
            AppText(
                text = (if (s.faceNumber == 1) "Face 2 item (not shown on face 1)" else "Face 2 item") +
                    (s.watchFaceCode?.takeIf { s.watchFace == null }?.let { ": unknown (code 0x%02X)".format(it) } ?: ""),
                modifier = Modifier.padding(top = 8.dp),
            )
            // Current choice highlighted (Auto = the phone switches Steps / Sun data around sunset).
            if (s.watchFace == null && s.watchFaceCode == null) org.avmedia.gshockGoogleSync.ui.common.LoadingSpinner()
            else faceLabels.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (item, label) ->
                        AppChoiceButton(
                            text = label,
                            selected = current == item && (item != null || s.dynamicFace),
                            onClick = { onFaceItem(item) },
                        )
                    }
                }
            }
            if (s.dynamicFace) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(text = "Sun data from: ${SunFace.formatLead(s.faceLeadMin)} before sunset", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(text = "−", onClick = {
                        vm.adjustFaceLead(-SunFace.LEAD_STEP_MIN)
                    })
                    org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(text = "+", onClick = {
                        vm.adjustFaceLead(SunFace.LEAD_STEP_MIN)
                    })
                }
            }
        }
    }
}

/** Sun & weather: the weather-on-the-sun-screen switch with the real values to compare against. */
@Composable
private fun SunWeatherCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            CardTitle("Sun & weather", "Weather instead of sunrise: the sun screen's top line shows W:TT, " +
                "W = 1 sunny, 2 cloudy, 3 rain/snow, 4 extreme (heavy rain, storm, gusts ≥ 60 km/h); TT = °C (0–59). " +
                "E.g. 1:18 = sunny 18 °C. Sunset stays real. Weather updates hourly while connected, not from 00:00 to 06:00 (saves the watch's battery); the watch is reminded of it every 10 min, day and night, so the screen never shows dashes for long.<br><br>" +
                "Real today and Weather now are the real values for the phone's location (Open-Meteo); " +
                "Watch should show is what the watch's sun screen should read now.<br><br>" +
                "Refresh fetches the weather now and sends location and time to the watch (the app also does " +
                "this hourly and on every connection).<br><br>" +
                "Fine-tune: if the top line is 1 min too low, press + on top; if sunset is 1 min late, press − on sunset.<br><br>" +
                "Sun at home (Auto face only): while the phone is on one of your home Wi-Fi networks, Auto shows Sun data " +
                "all day; away from them, the steps / sunset logic applies. It switches the moment the Wi-Fi connects or " +
                "drops. Android shows the Wi-Fi name only with precise location allowed all the time.")
            // Sun at home: only for Auto (a fixed Sun face has nothing to switch).
            if (s.dynamicFace) HomeSunRow(vm)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = if (s.dynamicFace) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(text = "Weather instead of sunrise")
                AppSwitch(checked = s.tempSun, onCheckedChange = { vm.setTempSun(it) })
            }
            // Details (real values, refresh, fine-tune) collapse behind one summary line.
            var details by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { details = !details },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = "Watch should show: " + s.watchShould.substringBefore(" (last").ifEmpty { "…" },
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Icon(
                    if (details) androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp
                    else androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (details) "Hide details" else "Show details",
                )
            }
            if (!details) return@Column
            // Real values to compare with the watch (phone location, Open-Meteo)
            AppText(text = s.realSun.replaceFirstChar { it.uppercase() }, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(text = "Weather now: ${s.realWeather}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = { vm.refreshSunWeather() }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Refresh,
                        contentDescription = "Refresh weather and send to the watch",
                    )
                }
            }
            s.watchShould.substringAfter(" (", "").takeIf { it.isNotEmpty() }?.let { last ->
                AppText(text = last.removeSuffix(")").replaceFirstChar { it.uppercase() }, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (s.tempSun) {
                // Fine-tune for the watch's own sun formula (measured: top +2, sunset -3)
                listOf(
                    Triple("Top line fine-tune", s.riseCorr, true),
                    Triple("Sunset fine-tune", s.setCorr, false),
                ).forEach { (label, value, isRise) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText(text = "$label: %+d min".format(value), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(text = "−", onClick = {
                                if (isRise) vm.adjustTempSun(-1, 0) else vm.adjustTempSun(0, -1)
                            })
                            org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(text = "+", onClick = {
                                if (isRise) vm.adjustTempSun(1, 0) else vm.adjustTempSun(0, 1)
                            })
                        }
                    }
                }
            }
        }
    }
}

/** Sound mode (+ raw packet), per-alarm note, last applied, and the notification app filter. */
@Composable
private fun SoundNotificationsCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            CardTitle("Sound & notifications", "Sound mode is the watch's own setting for all its sounds, and the " +
                "mode you pick here is kept. An alarm borrows the watch: 5 min before it rings the mode it needs is " +
                "set (Vibe only / Sound, chosen per alarm in the Alarms tab), and after the ring, or once its snooze is stopped, your " +
                "mode comes back.<br><br>Silent at night: the watch has no sound and no vibration from the start to " +
                "the end of the night time (set on the Settings tab); alarms still ring.<br><br>Notifications only " +
                "from chosen apps: when on, only the checked apps reach the watch; when off, every app's notifications do.")
            // Sound mode: the active button shows it; text only while it is not known yet.
            if (s.soundMode == null) AppText(
                text = if (s.busy) "Reading sound mode…" else "Sound mode unknown",
                modifier = Modifier.padding(top = 8.dp),
            )
            // All five modes; the current one is greyed out. Two rows so they fit on a phone.
            val modes = listOf(
                SoundMode.ALL_ON to "Sound + vibe", SoundMode.SOUND_ONLY to "Sound only", SoundMode.VIBE_ONLY to "Vibe only",
                SoundMode.ALL_OFF to "Off", SoundMode.CUSTOM to "Custom",
            )
            if (s.soundMode == null) org.avmedia.gshockGoogleSync.ui.common.LoadingSpinner() // never read
            else modes.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (mode, label) ->
                        AppChoiceButton(
                            text = label,
                            selected = s.soundMode == mode,
                            onClick = { vm.setSoundMode(mode) },
                        )
                    }
                }
            }

            s.lastApplied?.let { AppText(text = "Last applied: $it", fontSize = 13.sp) }
            // Do not disturb during night time (phone-side: the sound mode is Off through the night).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AppText(text = "Silent at night")
                    AppText(text = s.nightTimeText ?: "Night time not read yet: open the Settings tab once", fontSize = 13.sp)
                }
                AppSwitch(checked = s.nightDnd, onCheckedChange = { vm.setNightDnd(it) })
            }
            // Notification app filter
            var filter by remember { mutableStateOf(NotificationFilter.load(context)) }
            val appsScope = androidx.compose.runtime.rememberCoroutineScope()
            fun saveFilter(cfg: NotificationFilter.Config) {
                NotificationFilter.save(context, cfg)
                filter = cfg
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(text = "Notifications only from chosen apps")
                AppSwitch(checked = filter.enabled, onCheckedChange = { saveFilter(filter.copy(enabled = it)) })
            }
            org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(
                text = "Choose apps (${filter.packages.size})",
                onClick = { appsScope.launch {
                    // Listing and labelling every launcher app is slow: do it off the main thread.
                    val apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NotificationFilter.launchableApps(context) }
                    val checked = BooleanArray(apps.size) { apps[it].packageName in filter.packages }
                    AlertDialog.Builder(context)
                        .setTitle("Forward notifications from")
                        .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                        .setPositiveButton("Save") { _, _ ->
                            saveFilter(filter.copy(packages = apps.filterIndexed { i, _ -> checked[i] }.map { it.packageName }.toSet()))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } },
            )
        }
    }
}

private data class HrAsk(val item: WatchFaceItem?, val turnOn: Boolean) // item null = Auto

private fun hrModeLabel(m: org.avmedia.gshockapi.model.HeartRateMonitor): String = when (m) {
    org.avmedia.gshockapi.model.HeartRateMonitor.ALL_DAY -> "All day"
    org.avmedia.gshockapi.model.HeartRateMonitor.ACTIVITY_AND_NIGHT -> "Activities + night"
    org.avmedia.gshockapi.model.HeartRateMonitor.OFF -> "Off"
}

/**
 * Heart-rate monitor mode (packet 0x41), the three options of the CASIO app. Shown on the Settings tab.
 * Values from the CASIO app's decompiled code (Off 0, RunActOnly 1, AlwaysOn 2).
 */
@Composable
internal fun HeartRateMonitorCard(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    if (!s.supported) return
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            val options = listOf(
                org.avmedia.gshockapi.model.HeartRateMonitor.ALL_DAY to
                    "All functions: daytime heart rate, calories, sleep evaluation. High battery use.",
                org.avmedia.gshockapi.model.HeartRateMonitor.ACTIVITY_AND_NIGHT to
                    "Only during activities and at night; no daytime heart rate. Moderate battery use.",
                org.avmedia.gshockapi.model.HeartRateMonitor.OFF to "No heart-rate measurement. Lowest battery use.",
            )
            CardTitle("Heart-rate monitor", options.joinToString("<br><br>") { (m, d) -> "${hrModeLabel(m)}: $d" } +
                "<br><br>The Heart rate item on the watch face needs All day; the app asks when you pick it.")
            // Off while a smart alarm is on: ask first (smart alarms need heart-rate monitoring).
            var confirmOff by remember { mutableStateOf<List<String>?>(null) }
            if (s.hrMonitorCode == null) org.avmedia.gshockGoogleSync.ui.common.LoadingSpinner()
            else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                options.forEach { (m, _) ->
                    AppChoiceButton(text = hrModeLabel(m), selected = s.hrMonitor == m, onClick = {
                        val smart = vm.smartAlarms()
                        if (m == org.avmedia.gshockapi.model.HeartRateMonitor.OFF && smart.isNotEmpty()) confirmOff = smart
                        else vm.setHeartRateMonitor(m)
                    })
                }
            }
            confirmOff?.let { smart ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmOff = null },
                    title = { androidx.compose.material3.Text("Turn off heart-rate monitoring?") },
                    text = {
                        androidx.compose.material3.Text(
                            "Smart alarms need it to sense light sleep. Without it, " + smart.joinToString(", ") +
                                (if (smart.size == 1) " rings" else " ring") + " at the set time, with no early smart wake.",
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            confirmOff = null
                            vm.setHeartRateMonitor(org.avmedia.gshockapi.model.HeartRateMonitor.OFF)
                        }) { androidx.compose.material3.Text("Turn off") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmOff = null }) { androidx.compose.material3.Text("Keep on") }
                    },
                )
            }
            if (s.hrMonitorCode != null && s.hrMonitor == null)
                AppText(text = "Unknown mode (code 0x%02X)".format(s.hrMonitorCode), fontSize = 13.sp)
        }
    }
}

/**
 * "Sun at home" (Auto face): switch, home Wi-Fi name and where the phone is now. Switching it on
 * asks for precise location, then "Allow all the time" (Android hides the Wi-Fi name otherwise).
 */
@Composable
private fun HomeSunRow(vm: H5600ViewModel) {
    val s by vm.state.collectAsState()
    val background = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { vm.onHomePermissionResult() }
    val precise = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[android.Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        ) background.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else vm.onHomePermissionResult()
    }
    val askPermission = {
        precise.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(text = "Sun at home", modifier = Modifier.weight(1f))
        AppSwitch(checked = s.homeSun, onCheckedChange = { on ->
            vm.setHomeSun(on)
            if (on && !s.homePermission) askPermission()
        })
    }
    if (!s.homeSun) return
    if (!s.homePermission) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(text = "Needs location \"Allow all the time\"", fontSize = 13.sp, modifier = Modifier.weight(1f))
            org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(text = "Allow", onClick = askPermission)
        }
        return
    }
    val here = s.currentSsid
    // Summary line; tap it to open or close the list of home networks.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = when {
                s.homeSsids.isEmpty() -> "No home Wi-Fi yet"
                here != null && here in s.homeSsids -> "You are home ($here)"
                else -> "Away" + (here?.let { " ($it)" } ?: "")
            } + " · " + s.homeSsids.size + (if (s.homeSsids.size == 1) " home network" else " home networks"),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Icon(
            if (expanded) androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp
            else androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Hide home networks" else "Show home networks",
        )
    }
    if (!expanded) return
    // Home networks (any of them counts as home), each removable.
    s.homeSsids.forEach { ssid ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(text = ssid, modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = { vm.removeHomeWifi(ssid) }) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Remove $ssid",
                )
            }
        }
    }
    // Add: a dropdown of the networks the phone can see now (current first).
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        org.avmedia.gshockGoogleSync.ui.common.AppSmallButton(text = "Add home Wi-Fi", onClick = {
            vm.loadNearbyWifi(); open = true
        })
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val choices = s.nearbyWifi.filter { it !in s.homeSsids }
            if (choices.isEmpty()) androidx.compose.material3.DropdownMenuItem(
                text = { AppText(text = "No other networks in range", fontSize = 13.sp) }, onClick = { open = false }, enabled = false,
            )
            choices.forEach { ssid ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { AppText(text = ssid + if (ssid == here) "  (connected)" else "") },
                    onClick = { open = false; vm.addHomeWifi(ssid) },
                )
            }
        }
    }
}
