package org.avmedia.gshockGoogleSync.h5600

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.avmedia.gshockGoogleSync.data.repository.GShockRepository
import org.avmedia.gshockGoogleSync.ui.common.AppSnackbar
import org.avmedia.gshockapi.model.HeartRateMonitor
import org.avmedia.gshockapi.model.SoundMode
import org.avmedia.gshockapi.model.WatchFaceItem
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class H5600ViewModel @Inject constructor(
    private val api: GShockRepository,
    private val runner: H5600ScheduleRunner,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val supported: Boolean = false,
        val busy: Boolean = false,
        val soundMode: SoundMode? = null,
        val watchFace: WatchFaceItem? = null,
        val watchFaceCode: Int? = null,
        val soundHex: String = "",
        val faceNumber: Int? = null, // 1 = simple (weekday), 2 = customisable
        val dynamicFace: Boolean = false,
        val realSun: String = "",
        val realWeather: String = "",
        val watchShould: String = "",
        val tempSun: Boolean = false,
        val tempText: String = "",
        val riseCorr: Int = 0,
        val setCorr: Int = 0,
        val sunsetText: String = "",
        val faceLeadMin: Int = SunFace.DEFAULT_LEAD_MIN,
        val lastApplied: String? = null,
        val hrMonitorCode: Int? = null, // 0x41 byte 1 (raw, see HeartRateMonitor)
        // Settings / Sport tabs (read by refreshSettings): 0x13 night time, 0x4F physical, 0x43 goal
        val basic: org.avmedia.gshockapi.model.H5600Basic? = null,
        val physical: org.avmedia.gshockapi.model.H5600Physical? = null,
        val stepGoal: org.avmedia.gshockapi.model.StepGoal? = null,
        val modeList: List<Int>? = null, // 0x52, button order; left-out modes are hidden
        val nightDnd: Boolean = false, // do not disturb during night time (phone-side, via the sound mode)
        // "Sun at home" (Auto face): home Wi-Fi name, the Wi-Fi now, and whether the permission is there
        val homeSun: Boolean = false,
        val homeSsids: List<String> = emptyList(),
        val nearbyWifi: List<String> = emptyList(), // for the "Add" dropdown
        val currentSsid: String? = null,
        val homePermission: Boolean = false,
        val nightTimeText: String? = null, // the watch's night time as last read, e.g. "11:00 PM – 8:20 AM"
        val knownModes: List<Int> = emptyList(), // every mode this watch has shown (to bring hidden ones back)
    ) {
        val hrMonitor: HeartRateMonitor? get() = hrMonitorCode?.let(HeartRateMonitor::fromCode)
    }

    // Start from the last values read from the watch; null (spinner) only if never read.
    private val _state = MutableStateFlow(
        UiState(
            soundMode = org.avmedia.gshockGoogleSync.utils.LastKnown.int(context, "sound_mode")?.let(SoundMode::fromCode),
            soundHex = org.avmedia.gshockGoogleSync.utils.LastKnown.string(context, "sound_hex") ?: "",
            watchFaceCode = org.avmedia.gshockGoogleSync.utils.LastKnown.int(context, "watch_face"),
            faceNumber = org.avmedia.gshockGoogleSync.utils.LastKnown.int(context, "face_number"),
            watchFace = org.avmedia.gshockGoogleSync.utils.LastKnown.int(context, "watch_face")?.let(WatchFaceItem::fromCode),
            hrMonitorCode = org.avmedia.gshockGoogleSync.utils.LastKnown.int(context, "hr_monitor"),
        ),
    )
    val state: StateFlow<UiState> = _state

    // Declared before init: refresh() below already uses them (was a crash on opening the tab).
    // Watch actions run one after another in tap order, without blocking the UI: each change is
    // shown at once (optimistic), [busy] only drives the pull-to-refresh spinner.
    private val queue = kotlinx.coroutines.sync.Mutex()

    // Settings with a user change on its way to the watch. A read queued before the change (e.g. the
    // tab-open refresh) must not put the old value back on screen while the write is still pending.
    private val writing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Optimistic write of one setting: [key] is protected from older reads until [block] finishes. */
    private fun write(key: String, what: String, onError: () -> Unit, block: suspend () -> Unit) {
        writing += key
        run(what, onError) { try { block() } finally { writing -= key } }
    }

    private fun <T> fresh(key: String, read: T?, shown: T?): T? =
        if (key in writing || pendingWrites[key]?.isActive == true) shown else read ?: shown
    private val running = java.util.concurrent.atomic.AtomicInteger(0)

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("temp_sun") || key.startsWith("last_sent")) reloadPrefs()
    }

    init {
        // Sport / Settings values from the phone first (spinners only before the very first read),
        // saved again whenever one changes (a read or the user's edit).
        _state.update(loadSettingsCache())
        viewModelScope.launch {
            state.map { listOf(it.basic, it.physical, it.stepGoal, it.modeList, it.hrMonitorCode) }
                .distinctUntilChanged().drop(1).collect { saveSettingsCache() }
        }
        viewModelScope.launch { HomeWifi.currentSsid.collect { w -> _state.update { it.copy(currentSsid = w) } } }
        // Weather fetches and syncs run in the background (scheduler): show their values the moment
        // they are stored, not only when the tab is opened again.
        H5600Prefs.get(context).registerOnSharedPreferenceChangeListener(prefsListener)
        // The scheduler switches the sound mode around alarms and the night: show what it set.
        viewModelScope.launch {
            runner.soundModeSet.collect { m -> if (m != null) _state.update { it.copy(soundMode = m) } }
        }
        refresh()
        // Times on this tab are formatted when built: rebuild them when the 12h/24h choice changes.
        viewModelScope.launch {
            org.avmedia.gshockGoogleSync.utils.AppTimeFormat.is24h.drop(1).collect { reloadPrefs() }
        }
    }

    override fun onCleared() {
        H5600Prefs.get(context).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onCleared()
    }

    private fun reloadPrefs() = _state.update {
        val loc = org.avmedia.gshockGoogleSync.services.LocationProvider.getLocation(context)
        val zone = java.time.ZoneId.systemDefault()
        val set = loc?.let { l -> SunFace.sunset(java.time.LocalDate.now(), l.latitude, l.longitude, zone) }
        val today = java.time.LocalDate.now(zone)
        val realRs = loc?.let { l -> TempSun.riseSetUtcMin(today, l.latitude, l.longitude) }
        val fmt = { m: Double -> org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(TempSun.toLocal(today, m, zone).toLocalTime()) }
        val p = H5600Prefs.get(context)
        it.copy(
            realSun = realRs?.let { (r, st) -> "sunrise ${fmt(r)} · sunset ${fmt(st)}" } ?: "no location yet",
            realWeather = TempSun.last(context)?.let { (c, at) ->
                "%.1f °C, %s (%s) at %s".format(c, TempSun.categoryName(TempSun.lastCategory(context)),
                    TempSun.lastRaw(context) ?: "-",
                    org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(
                        java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(at), zone)))
            } ?: "not fetched yet",
            watchShould = realRs?.let { (r, st) ->
                val sunset = fmt(st)
                if (TempSun.isEnabled(context)) {
                    val top = TempSun.last(context)?.let { (c, _) ->
                        "%d:%02d".format(TempSun.lastCategory(context) ?: 0, Math.round(c).toInt().coerceIn(0, TempSun.MAX_TEMP))
                    } ?: "?"
                    val sent = p.getString(H5600Prefs.KEY_LAST_SENT_TOP, null)?.let { t ->
                        " (last sent $t at " + org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(
                            java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(p.getLong(H5600Prefs.KEY_LAST_SENT_AT, 0)), zone)) + ")"
                    } ?: ""
                    "$top / $sunset$sent"
                } else "${fmt(r)} / $sunset"
            } ?: "",
            tempSun = TempSun.isEnabled(context),
            riseCorr = TempSun.riseCorrection(context),
            setCorr = TempSun.setCorrection(context),
            tempText = TempSun.last(context)?.let { (c, at) ->
                "last " + (when (TempSun.lastCategory(context)) { 1 -> "sunny"; 2 -> "cloudy"; 3 -> "rain"; 4 -> "extreme"; else -> "?" }) +
                    ", %.1f °C at %s (Open-Meteo)".format(c,
                    org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(at), zone)))
            } ?: "no temperature yet",
            dynamicFace = SunFace.isEnabled(context),
            nightDnd = H5600Prefs.nightDndEnabled(context),
            homeSun = HomeWifi.isEnabled(context),
            homeSsids = HomeWifi.homeSsids(context),
            homePermission = HomeWifi.hasPermission(context),
            nightTimeText = H5600Prefs.nightTime(context)?.let { (a, b) ->
                val f = { m: Int -> org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(java.time.LocalTime.of(m / 60, m % 60)) }
                "${f(a)} – ${f(b)}"
            },
            faceLeadMin = SunFace.leadMin(context),
            sunsetText = set?.let { t ->
                "sunset %s → Sun data %s–%s".format(
                    org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(t.toLocalTime()),
                    org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(t.minusMinutes(SunFace.leadMin(context).toLong()).toLocalTime()),
                    org.avmedia.gshockGoogleSync.utils.AppTimeFormat.format(t.plusMinutes(SunFace.TRAIL_MIN).toLocalTime()),
                )
            } ?: "no location yet",
            lastApplied = H5600Schedule.lastApplied(context),
        )
    }

    fun refresh() {
        val supported = api.isH5600SettingsSupported()
        _state.update { it.copy(supported = supported) }
        reloadPrefs()
        if (!supported) return
        run("read") {
            // The library retries unanswered / rejected reads; a read that still fails keeps the
            // last known value. 0x42 is read once (mode = byte 1); the two 0x51 reads run together
            // and share one request.
            val hex = readOrNull { api.getH5600SoundPacketHex() }
            val mode = hex?.split(" ")?.getOrNull(1)?.toIntOrNull(16)?.let(SoundMode::fromCode)
            val (code, faceNo) = kotlinx.coroutines.coroutineScope {
                val c = async { readOrNull { api.getWatchFaceCode() } }
                val f = async { readOrNull { api.getH5600WatchFaceNumber() } }
                c.await() to f.await()
            }
            val hr = readOrNull { api.getH5600HeartRateMonitorCode() }
            Timber.i("H5600 re-read: 0x42=$hex mode=$mode face=$code")
            _state.update {
                val faceCode = fresh(KEY_FACE, code, it.watchFaceCode)
                it.copy(
                    soundMode = fresh(KEY_SOUND, mode, it.soundMode),
                    soundHex = hex ?: it.soundHex,
                    watchFaceCode = faceCode,
                    watchFace = if (KEY_FACE in writing) it.watchFace else faceCode?.let(WatchFaceItem::fromCode),
                    faceNumber = fresh(KEY_FACE_NO, faceNo, it.faceNumber),
                    hrMonitorCode = fresh(KEY_HR, hr, it.hrMonitorCode),
                )
            }
            mode?.let { m -> org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "sound_mode", m.code) }
            hex?.let { h -> org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "sound_hex", h) }
            code?.let { c -> org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "watch_face", c) }
            faceNo?.let { f -> org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "face_number", f) }
            hr?.let { h -> org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "hr_monitor", h) }
        }
    }

    /**
     * Read back after a write until the watch reports [expected]: it applies writes a moment
     * later, so an immediate read can still return the old value (seen: 70 ms after writing 0x51).
     * Returns the last value read (or [expected] if every read failed).
     */
    private suspend fun <T> confirm(expected: T, read: suspend () -> T?): T {
        var last: T? = null
        repeat(3) {
            val v = readOrNull { read() }
            if (v != null) last = v
            if (v == expected) return v
            kotlinx.coroutines.delay(1_200)
        }
        return last ?: expected
    }

    /** The user's choice: becomes the base mode the scheduler returns to after every alarm. */
    fun setSoundMode(mode: SoundMode) {
        H5600Prefs.setBaseSoundMode(context, mode)
        runner.forgetWatchState() // written outside the scheduler: its next pass reads the watch
        val previous = _state.value.soundMode
        _state.update { it.copy(soundMode = mode) } // move the UI immediately
        write(KEY_SOUND, "set sound mode", onError = { _state.update { it.copy(soundMode = previous) } }) {
            api.setSoundMode(mode)
            val v = confirm(mode) { api.getSoundMode() } // confirm from the watch
            _state.update { it.copy(soundMode = v) }
            org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "sound_mode", v.code)
        }
    }

    fun setFaceNumber(face: Int) {
        val previous = _state.value.faceNumber
        _state.update { it.copy(faceNumber = face) }
        write(KEY_FACE_NO, "switch watch face", onError = { _state.update { it.copy(faceNumber = previous) } }) {
            api.setH5600WatchFaceNumber(face)
            val f: Int? = confirm(face) { api.getH5600WatchFaceNumber() }
            _state.update { it.copy(faceNumber = f) }
            f?.let { org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "face_number", it) }
        }
    }

    fun setWatchFace(item: WatchFaceItem) {
        val previous = _state.value.watchFace
        _state.update { it.copy(watchFace = item) }
        write(KEY_FACE, "set watch face", onError = { _state.update { it.copy(watchFace = previous) } }) {
            api.setWatchFaceItem(item)
            val code = confirm(item.code) { api.getWatchFaceCode() }
            _state.update { it.copy(watchFaceCode = code, watchFace = WatchFaceItem.fromCode(code)) }
            org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "watch_face", code)
        }
    }

    /** Reads the values shown on the Settings tab (one after another: the watch answers one at a time). */
    /**
     * Reads the Sport / Settings values. The last values are shown at once (kept on the phone);
     * a read that fails — e.g. while a step sync keeps the watch busy — is retried every
     * [SETTINGS_RETRY_MS] for a few minutes instead of leaving the card waiting.
     */
    fun refreshSettings() {
        if (!api.isH5600SettingsSupported()) return
        settingsRetry?.cancel()
        readSettings(attempt = 0)
    }

    private var settingsRetry: kotlinx.coroutines.Job? = null

    private fun readSettings(attempt: Int): Unit = run("read settings") {
        val basic = readOrNull { api.getH5600Basic() }
        val physical = readOrNull { api.getH5600Physical() }
        val goal = readOrNull { api.getH5600StepGoal() }
        val hr = readOrNull { api.getH5600HeartRateMonitorCode() }
        val modes = readOrNull { api.getH5600ModeList() }
        basic?.let { H5600Prefs.saveNightTime(context, it.nightStartHour * 60 + it.nightStartMin, it.nightEndHour * 60 + it.nightEndMin) }
        val known = modes?.let { rememberModes(it) } ?: knownModes()
        _state.update {
            it.copy(
                modeList = fresh(KEY_MODES, modes, it.modeList), knownModes = known,
                basic = fresh(KEY_BASIC, basic, it.basic), physical = fresh(KEY_PHYSICAL, physical, it.physical),
                stepGoal = fresh(KEY_GOAL, goal, it.stepGoal),
                hrMonitorCode = fresh(KEY_HR, hr, it.hrMonitorCode),
            )
        }
        saveSettingsCache()
        val missed = listOf(basic, physical, goal, hr, modes).any { it == null }
        if (missed && attempt < SETTINGS_RETRIES) {
            Timber.i("H5600 settings read incomplete (attempt ${attempt + 1}), retrying")
            settingsRetry = viewModelScope.launch {
                kotlinx.coroutines.delay(SETTINGS_RETRY_MS)
                readSettings(attempt + 1)
            }
        }
    }

    // ---- Sport / Settings values kept on the phone, shown before the watch answers ----

    private fun saveSettingsCache() {
        val st = _state.value
        val o = org.json.JSONObject()
        st.basic?.let { b -> o.put("basic", org.json.JSONArray(listOf(if (b.stepReminder) 1 else 0, b.nightStartHour, b.nightStartMin, b.nightEndHour, b.nightEndMin))) }
        st.physical?.let { p -> o.put("physical", org.json.JSONArray(listOf(p.sleepStartHour, p.sleepStartMin, p.wakeHour, p.wakeMin, p.maxHr, if (p.maxHrManual) 1 else 0, p.restingHr, if (p.restingHrManual) 1 else 0, p.vo2Max))) }
        st.stepGoal?.let { g -> o.put("goal", org.json.JSONArray(listOf(g.steps, if (g.notifyWhenReached) 1 else 0))) }
        st.modeList?.let { m -> o.put("modes", org.json.JSONArray(m)) }
        org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "h5600_settings", o.toString())
        st.hrMonitorCode?.let { org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "hr_monitor", it) }
    }

    private fun loadSettingsCache(): UiState.() -> UiState = {
        runCatching {
            val o = org.json.JSONObject(org.avmedia.gshockGoogleSync.utils.LastKnown.string(context, "h5600_settings") ?: "{}")
            val ints = { k: String -> o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) } } }
            copy(
                basic = ints("basic")?.let { v -> org.avmedia.gshockapi.model.H5600Basic(v[0] == 1, v[1], v[2], v[3], v[4]) },
                physical = ints("physical")?.let { v -> org.avmedia.gshockapi.model.H5600Physical(v[0], v[1], v[2], v[3], v[4], v[5] == 1, v[6], v[7] == 1, v[8]) },
                stepGoal = ints("goal")?.let { v -> org.avmedia.gshockapi.model.StepGoal(v[0], v[1] == 1) },
                modeList = ints("modes"),
                knownModes = knownModes(),
            )
        }.getOrDefault(this)
    }

    // Quick taps (+/- buttons, toggles): the UI moves at once, one watch write after the last tap.
    private val pendingWrites = mutableMapOf<String, kotlinx.coroutines.Job>()

    private fun debouncedWrite(key: String, what: String, block: suspend () -> Unit) {
        pendingWrites[key]?.cancel()
        pendingWrites[key] = viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            run(what, onError = { refreshSettings() }, block = block)
        }
    }

    fun setStepReminder(on: Boolean) {
        val b = _state.value.basic ?: return
        val nb = b.copy(stepReminder = on)
        _state.update { it.copy(basic = nb) }
        debouncedWrite(KEY_BASIC, "step reminder") { api.setH5600Basic(nb) }
    }

    /** Saves a new mode order / visibility (one write); Time & date always stays first. */
    fun setModeList(modes: List<Int>) {
        val list = listOf(TIME_AND_DATE) + modes.filter { it != TIME_AND_DATE }
        val before = _state.value.modeList
        _state.update { it.copy(modeList = list) }
        // One write per Save (the card keeps edits as a draft until then).
        write(KEY_MODES, "watch modes", onError = { _state.update { it.copy(modeList = before) } }) { api.setH5600ModeList(list) }
    }

    // Every mode the watch has listed, in first-seen order (hidden modes vanish from 0x52).
    private fun knownModes(): List<Int> =
        H5600Prefs.get(context).getString(KEY_KNOWN_MODES, null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?: DEFAULT_MODES

    private fun rememberModes(current: List<Int>): List<Int> {
        val known = (knownModes() + current).distinct()
        H5600Prefs.get(context).edit().putString(KEY_KNOWN_MODES, known.joinToString(",")).apply()
        return known
    }

    fun setPhysical(v: org.avmedia.gshockapi.model.H5600Physical) {
        _state.update { it.copy(physical = v) }
        debouncedWrite(KEY_PHYSICAL, "heart-rate / sleep settings") { api.setH5600Physical(v) }
    }

    /**
     * Night time: the watch keeps it twice, in 0x13 (notifications) and 0x4F (sleep / HR); the
     * official app writes the same times to both.
     */
    fun setNightTime(startH: Int, startM: Int, endH: Int, endM: Int) {
        val b = _state.value.basic ?: return
        val p = _state.value.physical
        val nb = b.copy(nightStartHour = startH, nightStartMin = startM, nightEndHour = endH, nightEndMin = endM)
        val np = p?.copy(sleepStartHour = startH, sleepStartMin = startM, wakeHour = endH, wakeMin = endM)
        _state.update { it.copy(basic = nb, physical = np ?: it.physical) }
        write(KEY_BASIC, "night time", onError = { refreshSettings() }) {
            api.setH5600Basic(nb)
            if (np != null) api.setH5600Physical(np)
            H5600Prefs.saveNightTime(context, startH * 60 + startM, endH * 60 + endM)
            reloadPrefs()
            if (H5600Prefs.nightDndEnabled(context)) runner.applyAlarms() // re-plan the night silence
        }
    }

    /** Do not disturb during night time: the sound mode goes to Off for the night, alarms excepted. */
    fun setNightDnd(on: Boolean) {
        H5600Prefs.setNightDndEnabled(context, on)
        _state.update { it.copy(nightDnd = on) }
        run("do not disturb") { runner.applyAlarms() }
    }

    fun setStepGoal(g: org.avmedia.gshockapi.model.StepGoal) {
        _state.update { it.copy(stepGoal = g) }
        debouncedWrite(KEY_GOAL, "step goal") { api.setH5600StepGoal(g) }
    }

    /** Sets the heart-rate monitor mode (0x41) and confirms it from the watch. */
    fun setHeartRateMonitor(mode: HeartRateMonitor) {
        val previous = _state.value.hrMonitorCode
        _state.update { it.copy(hrMonitorCode = mode.code) }
        write(KEY_HR, "set heart-rate monitor", onError = { _state.update { it.copy(hrMonitorCode = previous) } }) {
            writeHeartRateMonitor(mode)
        }
    }

    private suspend fun writeHeartRateMonitor(mode: HeartRateMonitor) {
        val before = api.setH5600HeartRateMonitor(mode)
        // Remember the mode used before all-day monitoring, to go back to it when HR leaves the face.
        if (mode == HeartRateMonitor.ALL_DAY && before != null && before != HeartRateMonitor.ALL_DAY.code) {
            H5600Prefs.get(context).edit().putInt(KEY_HR_BEFORE_ALL_DAY, before).apply()
        }
        val code = confirm(mode.code) { api.getH5600HeartRateMonitorCode() }
        _state.update { it.copy(hrMonitorCode = code) }
        org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "hr_monitor", code)
    }

    /** The mode to go back to when all-day monitoring is turned off from the watch-face dialog. */
    fun hrModeWhenOff(): HeartRateMonitor =
        H5600Prefs.get(context).getInt(KEY_HR_BEFORE_ALL_DAY, -1).let(HeartRateMonitor::fromCode)
            ?.takeIf { it != HeartRateMonitor.ALL_DAY }
            // smart alarms need heart-rate monitoring: never fall back to Off while one is on
            ?.takeIf { it != HeartRateMonitor.OFF || smartAlarms().isEmpty() }
            ?: HeartRateMonitor.ACTIVITY_AND_NIGHT

    /**
     * Enabled smart alarms, as "08:10 Weekdays". The watch's smart alarm needs heart-rate
     * measurement All day or Activities + night (Casio 3516 manual); with Off it cannot sense light sleep.
     */
    fun smartAlarms(): List<String> =
        AlarmPool.load(context).orEmpty().filter { it.enabled && it.isSmart }
            .map { a -> org.avmedia.gshockGoogleSync.utils.AppTimeFormat.hm(a.hour, a.minute) + (if (a.label.isEmpty()) "" else " " + a.label) }

    /**
     * Face-2 item choice: [item] null = Auto (Steps / Sun data around sunset, driven by the phone),
     * otherwise a fixed item (Auto is switched off). Then, if the user agreed in the HR dialog, the
     * heart-rate monitor mode.
     */
    fun selectFace(item: WatchFaceItem?, hrMode: HeartRateMonitor?) {
        if (item == null) setDynamicFace(true)
        else {
            if (_state.value.dynamicFace) setDynamicFace(false)
            setWatchFace(item)
        }
        if (hrMode != null) setHeartRateMonitor(hrMode)
    }

    /** On: fetch the temperature now and send the fake coordinates. Off: send the real location again. */
    fun setTempSun(on: Boolean) {
        TempSun.setEnabled(context, on)
        reloadPrefs()
        run(if (on) "temperature sunrise" else "real sunrise") {
            val msg = if (on) runner.refreshTempSun(force = true)
            else { runner.syncLocationAndTime(); "real sunrise restored" }
            reloadPrefs()
            msg?.let { AppSnackbar(it) }
        }
    }

    /** Fine-tune the watch-formula corrections, then re-send the coordinates + time sync. */
    fun adjustTempSun(riseDelta: Int, setDelta: Int) {
        TempSun.saveCorrections(context, TempSun.riseCorrection(context) + riseDelta, TempSun.setCorrection(context) + setDelta)
        reloadPrefs()
        if (TempSun.isEnabled(context)) run("re-send temperature sunrise") { runner.syncLocationAndTime() }
    }

    /** Fetch the weather for display only (nothing is written to the watch). */
    /**
     * Refresh: fetch the weather now, then send location + time to the watch so its sun screen is
     * recalculated (with the weather encoded when "Weather instead of sunrise" is on).
     */
    fun refreshSunWeather() = run("refresh") {
        val msg = if (TempSun.isEnabled(context)) runner.refreshTempSun(force = true)
        else {
            val loc = org.avmedia.gshockGoogleSync.services.LocationProvider.getLocation(context) ?: error("no location")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TempSun.fetchTemperature(context, loc.latitude, loc.longitude)
            }
            runner.syncLocationAndTime()
            "weather fetched, location and time sent"
        }
        reloadPrefs()
        msg?.let { AppSnackbar(it) }
    }

    /** On: the face follows the sunset window right away. Off: the face stays as it is. */
    fun setDynamicFace(on: Boolean) {
        SunFace.setEnabled(context, on)
        reloadPrefs()
        if (on) run("dynamic face") {
            runner.applySunFace() // only the face, not the whole scheduler
            reloadPrefs()
            readOrNull { api.getWatchFaceCode() }?.let { code ->
                _state.update { it.copy(watchFaceCode = code, watchFace = WatchFaceItem.fromCode(code)) }
                org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "watch_face", code)
            }
        }
    }

    /** "Sun at home" on/off; switching it on saves the current Wi-Fi as home if none is saved yet. */
    fun setHomeSun(on: Boolean) {
        HomeWifi.setEnabled(context, on)
        if (on && HomeWifi.homeSsids(context).isEmpty()) HomeWifi.currentSsid.value?.let { HomeWifi.addHomeSsid(context, it) }
        reloadPrefs()
        run("sun at home") { runner.applySunFace() }
    }

    /** Networks for the "Add" dropdown (what the phone sees now). */
    fun loadNearbyWifi() = _state.update { it.copy(nearbyWifi = HomeWifi.nearby(context)) }

    fun addHomeWifi(ssid: String) {
        HomeWifi.addHomeSsid(context, ssid)
        reloadPrefs()
        run("sun at home") { runner.applySunFace() }
    }

    fun removeHomeWifi(ssid: String) {
        HomeWifi.removeHomeSsid(context, ssid)
        reloadPrefs()
        run("sun at home") { runner.applySunFace() }
    }

    /** After the location permission dialogs: re-register the Wi-Fi callback so the name becomes visible. */
    fun onHomePermissionResult() {
        runner.startHomeWifi()
        reloadPrefs()
    }

    /** Lead time before sunset; with the toggle on, the face is re-checked and the switch re-planned. */
    fun adjustFaceLead(deltaMin: Int) {
        SunFace.setLeadMin(context, SunFace.leadMin(context) + deltaMin)
        reloadPrefs()
        if (SunFace.isEnabled(context)) {
            faceLeadJob?.cancel()
            faceLeadJob = viewModelScope.launch {
                kotlinx.coroutines.delay(800) // quick taps: one watch check after the last one
                run("face lead time") {
                    runner.applySunFace()
                    reloadPrefs()
                    readOrNull { api.getWatchFaceCode() }?.let { code ->
                        _state.update { it.copy(watchFaceCode = code, watchFace = WatchFaceItem.fromCode(code)) }
                    }
                }
            }
        }
    }

    private var faceLeadJob: kotlinx.coroutines.Job? = null

    /**
     * The watch only recalculates sunrise/sunset when the time / cities are written, so a bare
     * location packet is not enough: a full time sync (Fine Adjustment and time-zone option as on
     * the Time tab) with the location written inside it.
     */
    private fun run(what: String, onError: () -> Unit = {}, block: suspend () -> Unit) {
        _state.update { it.copy(busy = running.incrementAndGet() > 0) }
        viewModelScope.launch {
            try { queue.withLock { runOne(what, onError, block) } } finally {
                _state.update { it.copy(busy = running.decrementAndGet() > 0) }
            }
        }
    }

    private suspend fun runOne(what: String, onError: () -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e // screen left: not a failure
            Timber.e(e, "H5600 $what timed out")
            onError()
            AppSnackbar("DW-H5600: $what timed out")
        } catch (e: Exception) {
            Timber.e(e, "H5600 $what failed")
            onError()
            AppSnackbar("DW-H5600: $what failed: ${e.message}")
        }
    }

    /** A watch read whose failure keeps the last known value; real cancellation is rethrown. */
    companion object {
        private const val KEY_HR_BEFORE_ALL_DAY = "hr_before_all_day"
        private const val KEY_KNOWN_MODES = "h5600_known_modes"
        private const val KEY_FACE = "face"
        private const val KEY_FACE_NO = "face_no"
        private const val KEY_SOUND = "sound"
        private const val KEY_HR = "hr"
        private const val KEY_BASIC = "basic"
        private const val KEY_PHYSICAL = "physical"
        private const val KEY_GOAL = "goal"
        private const val KEY_MODES = "modes"
        private const val SETTINGS_RETRY_MS = 30_000L
        private const val SETTINGS_RETRIES = 6 // 3 min: longer than a step sync
        const val TIME_AND_DATE = 0x01
        // The user's DW-H5600 list as first read (2026-11-17), before anything was hidden.
        private val DEFAULT_MODES = listOf(0x01, 0x0F, 0x0E, 0x11, 0x10, 0x02, 0x08, 0x09, 0x0A, 0x03, 0x05, 0x06)
        /** Face items that need the watch to measure heart rate during the day. */
        val HR_ITEMS = setOf(WatchFaceItem.HEART_RATE, WatchFaceItem.HEART_RATE_MIN_MAX)
    }

    private suspend fun <T> readOrNull(block: suspend () -> T?): T? =
        try {
            kotlinx.coroutines.withTimeoutOrNull(12_000) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("H5600 read failed: ${e.message}"); null
        }
}
