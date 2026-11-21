package org.avmedia.gshockGoogleSync.h5600

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockGoogleSync.data.repository.GShockRepository
import org.avmedia.gshockGoogleSync.session.Session
import org.avmedia.gshockGoogleSync.services.LocationProvider
import org.avmedia.gshockGoogleSync.session.SessionPhase
import org.avmedia.gshockGoogleSync.utils.AppTimeFormat
import org.avmedia.gshockapi.model.SmartAlarm
import org.avmedia.gshockapi.model.SoundMode
import timber.log.Timber
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side scheduler for what the DW-H5600 cannot do itself:
 *  - alarms ([AlarmPool]): loads the next alarms into the 4 slots + the smart-alarm slot, sets the
 *    watch's single sound mode for the alarm due next, switches finished one-time alarms off, and
 *    snoozes: moves a snoozing alarm's slot to its next repeat a minute after each ring, until the
 *    snooze is stopped from the phone (the watch's own snooze is never used);
 *  - sun data: a loc/time sync on connect and hourly, not overnight ([SyncPacing]) (weather trick: [TempSun]);
 *  - dynamic watch face ([SunFace]) at the edges of the sunset window.
 * Runs once when the watch connects (before the regular connection actions) and, while it stays
 * connected, each part only when it is due.
 */
@Singleton
class H5600ScheduleRunner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: GShockRepository,
    private val watchTimeUpdater: org.avmedia.gshockGoogleSync.ui.actions.WatchTimeUpdater,
) {
    private val mutex = Mutex()

    // ---- Background queue: screens hand work over and stay responsive ----

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private val running = java.util.concurrent.atomic.AtomicInteger(0)
    private val _syncing = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True while queued watch work is running or waiting (shown as "sending to watch…"). */
    val syncing: kotlinx.coroutines.flow.StateFlow<Boolean> = _syncing

    private fun background(what: String, block: suspend () -> Unit) {
        _syncing.value = running.incrementAndGet() > 0
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "H5600 $what failed")
            } finally {
                _syncing.value = running.decrementAndGet() > 0
            }
        }
    }

    @Volatile private var alarmsQueued = false

    private val _alarmsPending = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * True when the watch has not got the latest alarm list yet (the last update failed). The phone
     * list is the source of truth, so a failure is retried — never rolled back: after 10 s, 30 s,
     * 1 min, 2 min, then on every pass the scheduler makes (connect, alarm times).
     */
    val alarmsPending: kotlinx.coroutines.flow.StateFlow<Boolean> = _alarmsPending
    private val retryDelaysMs = listOf(10_000L, 30_000L, 60_000L, 120_000L)
    @Volatile private var retryAttempt = 0
    private var retryJob: kotlinx.coroutines.Job? = null

    private fun onAlarmPass(ok: Boolean) {
        _alarmsPending.value = !ok
        retryJob?.cancel()
        if (ok) { retryAttempt = 0; return }
        val wait = retryDelaysMs.getOrNull(retryAttempt++) ?: return // later: next connect / alarm boundary
        Timber.w("H5600 alarm update failed; retry ${retryAttempt} in ${wait / 1000} s")
        retryJob = scope.launch { delay(wait); requestApplyAlarms() }
    }

    /**
     * Alarms tab edits: the list is already saved on the phone; this brings the watch in line in the
     * background. Edits made while an update is still waiting are merged into it (it reads the
     * saved list once it runs), and updates run one after another.
     */
    fun requestApplyAlarms() {
        if (alarmsQueued) return
        alarmsQueued = true
        background("alarm update") {
            mutex.withLock {
                alarmsQueued = false // later edits queue a new pass; this one sees all edits up to now
                if (api.isH5600SettingsSupported()) {
                    val notes = mutableListOf<String>()
                    onAlarmPass(applyAlarmsLocked(notes))
                    record(notes)
                }
            }
            wake.trySend(Unit)
        }
    }

    /** The "snoozing · next HH:MM · Stop snooze" notification for each snoozing alarm. */
    private fun showSnoozing(snoozing: List<Pair<AlarmPool.PhoneAlarm, LocalDateTime>>) =
        SnoozeNotifier.update(context, snoozing)

    /** Stop snooze from a screen or the notification, in the background. */
    fun requestStopSnooze(id: String) = background("stop snooze") { stopSnooze(id) }

    /** (Re)starts following the Wi-Fi for "Sun at home"; also called after the permission is granted. */
    fun startHomeWifi() = HomeWifi.start(context) { background("home wi-fi") { applySunFace() } }

    init {
        startHomeWifi()
        // Debug builds: protocol probing over adb, e.g.
        //   adb shell am broadcast -a org.avmedia.gshockGoogleSync.RAW --es get 52
        //   adb shell am broadcast -a org.avmedia.gshockGoogleSync.RAW --es set "13 04 01 ..."
        // Replies appear in logcat ("Notification from ...").
        if (org.avmedia.gshockGoogleSync.BuildConfig.DEBUG) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    val parse = { h: String -> h.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.toInt(16) }.toIntArray() }
                    runCatching {
                        i.getStringExtra("get")?.let { api.h5600RawSend(true, parse(it)) }
                        i.getStringExtra("set")?.let { api.h5600RawSend(false, parse(it)) }
                    }.onFailure { timber.log.Timber.e(it, "RAW probe failed") }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context, receiver, android.content.IntentFilter("org.avmedia.gshockGoogleSync.RAW"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }
        // Every time sync (from any screen, any moment) writes this location inside it
        // (H5600SettingsIO.writeLocationInTimeSync). Fresh each time, real or weather coordinates.
        api.setH5600HomeLocationProvider { effectiveLocation() }
        // The Time-tab clock marked "on the watch" becomes the watch's world city on every time sync.
        api.setWorldCityProvider {
            org.avmedia.gshockGoogleSync.ui.time.WorldClocks.watchClock(context)?.let { it.zone to it.name }
        }
    }

    /** The world city changed on the Time tab: sync now, in the background. */
    fun requestWorldCitySync() = background("world city") { syncLocationAndTime() }

    /**
     * Coordinates to send in 0x24: the phone's location, or, with "Temperature instead of sunrise"
     * on and a temperature known, fake coordinates whose sunrise reads 0:TT (see [TempSun]).
     */
    private fun effectiveLocation(): Pair<Double, Double>? {
        val loc = LocationProvider.getLocation(context) ?: return null
        if (TempSun.isEnabled(context)) {
            val temp = TempSun.last(context)?.first
            if (temp != null) {
                val zone = ZoneId.systemDefault()
                TempSun.fakeCoordinates(
                    LocalDate.now(zone), loc.latitude, loc.longitude, zone, Math.round(temp).toInt(),
                    TempSun.riseCorrection(context), TempSun.setCorrection(context),
                    riseHour = TempSun.lastCategory(context) ?: 0, // W:TT, W = 1 sunny / 2 cloudy / 3 rain
                )?.also {
                    H5600Prefs.get(context).edit()
                        .putString(H5600Prefs.KEY_LAST_SENT_TOP, "%d:%02d".format(TempSun.lastCategory(context) ?: 0,
                            Math.round(temp).toInt().coerceIn(0, TempSun.MAX_TEMP)))
                        .putLong(H5600Prefs.KEY_LAST_SENT_AT, System.currentTimeMillis()).apply()
                }
                    ?.let { return it }
            }
        }
        return loc.latitude to loc.longitude
    }

    /**
     * Temperature trick: fetch Open-Meteo when due (every [TempSun.REFRESH_MIN] min, or [force]),
     * then loc/time sync so the watch recalculates with the fake coordinates. Returns a status text.
     */
    suspend fun refreshTempSun(force: Boolean): String? = mutex.withLock { refreshTempSunLocked(force) }

    private suspend fun refreshTempSunLocked(force: Boolean): String? {
        if (!TempSun.isEnabled(context) || !api.isH5600SettingsSupported()) return null
        if (!force && !TempSun.isStale(context)) return null
        val loc = LocationProvider.getLocation(context) ?: return "temp: no location"
        val notes = mutableListOf<String>()
        val text = watchCall("temp", notes) {
            val c = withContext(Dispatchers.IO) { TempSun.fetchTemperature(context, loc.latitude, loc.longitude) }
            syncTimeAndLocation()
            "weather %d, %.1f °C → sunrise %d:%02d".format(
                TempSun.lastCategory(context) ?: 0, c,
                TempSun.lastCategory(context) ?: 0, Math.round(c).toInt().coerceIn(0, TempSun.MAX_TEMP),
            )
        } ?: notes.firstOrNull()
        return text.also { Timber.i("H5600 $it") }
    }

    /** Hands the location to the library, which writes it inside the next time sync (TimeIO). */
    private fun rememberLocation() {
        effectiveLocation()?.let { (lat, lon) -> api.rememberH5600HomeLocation(lat, lon) }
    }

    /** Time sync (location written inside it); the watch recalculates sun data only on such a write. */
    private suspend fun syncTimeAndLocation() {
        rememberLocation()
        withTimeout(20_000) { watchTimeUpdater.updateTime() }
        lastSunResend = LocalDateTime.now()
    }

    @Volatile private var lastSunResend: LocalDateTime? = null

    /**
     * What the watch holds, as last read from it or written to it on this connection: lets a planned
     * alarm pass skip the watch entirely when nothing has to change (battery). Null = unknown, read
     * it. Cleared on every connect/disconnect, any failed pass, any write from elsewhere
     * ([forgetWatchState]) and after [WATCH_STATE_MAX_AGE_H] hours (the watch may drift on its own).
     */
    private data class WatchState(
        val alarms: List<org.avmedia.gshockapi.model.Alarm>,
        val smart: SmartAlarm?,
        val sound: SoundMode,
        val at: LocalDateTime,
    )
    @Volatile private var watchState: WatchState? = null

    /** Something other than the scheduler wrote to the watch: the next pass reads it again. */
    fun forgetWatchState() { watchState = null }

    /** "Send loc/time" button, temperature-trick corrections, real sunrise restored. */
    suspend fun syncLocationAndTime() = mutex.withLock {
        if (api.isH5600SettingsSupported()) syncTimeAndLocation()
    }

    /**
     * The watch sometimes drops its sun location on its own (seen 2026-11-16: dashes with no write
     * from us). With the weather option off nothing else re-sends it, so do a loc/time sync hourly
     * outside the quiet hours (the weather option does the same via its hourly fetch).
     */
    private suspend fun resendSunIfDue(): String? {
        if (TempSun.isEnabled(context) || !api.isH5600SettingsSupported()) return null
        val last = lastSunResend
        if (last != null && LocalDateTime.now().isBefore(TempSun.nextFetch(last))) return null
        return watchCall("sun re-send") { syncTimeAndLocation(); "sun data re-sent" }
    }

    /** Called once per connection when the session is Ready. */
    suspend fun onConnected(applySchedule: Boolean) {
        if (!api.isH5600SettingsSupported()) return
        watchState = null // a new connection: read what the watch really has
        watchCall("0x39 handshake") { withTimeout(15_000) { api.h5600ConnectionHandshake() } }
        // Smart Sync's connect routine rewrites the home / world-city packets (1D/1E/1F), which makes
        // the watch drop its sun location -> dashes. One loc/time sync per connection fixes it, and
        // also counts as this hour's sun re-send. (A time sync would cancel the watch's own
        // snooze, but the phone does the snoozing, so syncs never have to wait.)
        // Weather due as well? Fetch it first, so this one sync carries it — instead of a second
        // full time sync from the weather refresh a few seconds later.
        if (TempSun.isEnabled(context) && TempSun.isStale(context)) {
            LocationProvider.getLocation(context)?.let { loc ->
                watchCall("weather on connect") {
                    withContext(Dispatchers.IO) { TempSun.fetchTemperature(context, loc.latitude, loc.longitude) }
                }
            }
        }
        watchCall("loc/time sync on connect") { syncTimeAndLocation() }
        if (applySchedule) applyIfEnabled()
    }

    /**
     * Stops alarm [id]'s snooze: saved on the phone first (it is the source of truth), then the
     * slot goes back to the alarm's own time (or the next alarm), the sound mode to the next alarm /
     * the user's, and a one-time alarm switches off. A failed watch write is retried like any edit.
     */
    suspend fun stopSnooze(id: String) = mutex.withLock {
        val now = LocalDateTime.now()
        val pool = AlarmPool.load(context) ?: return@withLock
        val target = AlarmPool.snoozing(pool, now).firstOrNull { it.first.id == id } ?: return@withLock
        val stopped = AlarmPool.dismiss(pool, id, now)
        AlarmPool.save(context, stopped)
        if (!api.isH5600SettingsSupported()) { // not connected: the watch gets it on the next connect
            showSnoozing(AlarmPool.snoozing(stopped, now))
            onPoolChanged?.invoke()
            return@withLock
        }
        val notes = mutableListOf<String>()
        notes += "snooze stopped: " + AppTimeFormat.hm(target.first.hour, target.first.minute)
        onAlarmPass(applyAlarmsLocked(notes))
        record(notes)
        onPoolChanged?.invoke()
        wake.trySend(Unit)
    }

    /** Everything now: alarms, sound mode, smart alarm, sun/weather refresh if due, watch face. */
    suspend fun applyIfEnabled() = mutex.withLock {
        if (!api.isH5600SettingsSupported()) return@withLock
        val notes = mutableListOf<String>()
        onAlarmPass(applyAlarmsLocked(notes))
        applyPeriodicLocked(notes)
        applySunFaceLocked(notes)
        record(notes)
    }

    /** The sound mode the scheduler last set (every open tab's view model follows it). */
    val soundModeSet = kotlinx.coroutines.flow.MutableStateFlow<SoundMode?>(null)

    /** Called after each alarm pass (one-time alarm switched off, snooze window started or ended). */
    @Volatile var onPoolChanged: (() -> Unit)? = null

    /** Only the alarm part (Alarms tab edits). Also wakes the boundary loop to re-plan. */
    suspend fun applyAlarms() = mutex.withLock {
        if (!api.isH5600SettingsSupported()) return@withLock
        val notes = mutableListOf<String>()
        onAlarmPass(applyAlarmsLocked(notes))
        record(notes)
        wake.trySend(Unit)
    }

    /** Exact Android alarm fired (see [SchedulerWakeup]): run the loop now, even if the phone dozes. */
    fun onWakeupAlarm() {
        SchedulerWakeup.bridge(context)
        wake.trySend(Unit)
    }

    /** Wakes the boundary loop early (alarms changed), so it re-plans its next wake-up. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Only the dynamic watch face (Custom-tab switch). */
    suspend fun applySunFace() = mutex.withLock {
        if (!api.isH5600SettingsSupported()) return@withLock
        val notes = mutableListOf<String>()
        applySunFaceLocked(notes)
        if (notes.isNotEmpty()) record(notes)
        wake.trySend(Unit) // re-plan the next face switch (the switch or its lead time changed)
    }

    private fun record(notes: List<String>) {
        val now = LocalDateTime.now()
        val stamp = now.format(DateTimeFormatter.ofPattern("EEE dd MMM ")) + AppTimeFormat.format(now.toLocalTime())
        Timber.i("H5600 schedule: $notes")
        H5600Schedule.recordApplied(context, "$stamp: " + (notes.joinToString("; ").ifEmpty { "no change needed" }))
    }

    /**
     * Alarm slots (snoozing alarms at their next repeat), smart-alarm slot and sound mode for the
     * alarm due next.
     */
    private suspend fun applyAlarmsLocked(notes: MutableList<String>, planned: Boolean = false): Boolean {
        val loaded = AlarmPool.load(context) ?: return true // null until the Alarms tab has been opened once
        val now = LocalDateTime.now()
        // One-time alarms that have rung (and whose snooze was stopped) switch themselves off.
        val pool = AlarmPool.expireOnce(loaded, now)
        if (pool != loaded) {
            AlarmPool.save(context, pool)
            notes += "one-time alarm off: " + pool.filterIndexed { i, a -> !a.enabled && loaded[i].enabled }
                .joinToString(" ") { AppTimeFormat.hm(it.hour, it.minute) }
        }
        // Planned passes (boundary loop) trust the known watch state; user actions read the watch.
        val known = watchState?.takeIf { planned && Duration.between(it.at, now).toHours() < WATCH_STATE_MAX_AGE_H }
        if (known != null) {
            val plan = AlarmPool.plan(pool, AlarmPool.loadedIds(context), now)
            val wanted = AlarmPool.toWatchAlarms(plan, pool, known.alarms, now)
            val smartId = AlarmPool.planSmart(pool, now)
            val smart = pool.firstOrNull { it.id == smartId }
            val wantedSmart = known.smart?.let { cur ->
                smart?.let { SmartAlarm(it.hour, it.minute, true, false, it.smartWindowMin) } ?: cur.copy(enabled = false)
            }
            val base = AlarmPool.baseAt(H5600Prefs.baseSoundMode(context), H5600Prefs.nightDnd(context), now)
            val desired = AlarmPool.desiredSoundMode(pool, now, base)
            if (AlarmPool.sameOnWatch(wanted, known.alarms) && wantedSmart == known.smart && desired == known.sound) {
                Timber.i("H5600 planned alarm pass: watch already matches, not contacted")
                AlarmPool.saveLoadedIds(context, plan)
                AlarmPool.saveLoadedSmartId(context, smartId)
                showSnoozing(AlarmPool.snoozing(pool, now))
                onPoolChanged?.invoke()
                return true
            }
        }
        watchState = null // re-learnt below; stays unknown if the pass fails
        var learnt: WatchState? = null
        val ok = watchCall("alarm schedule", notes) {
            withTimeout(45_000) {
                // Writing anyway: start from the known slots (no read) when they are fresh.
                val watch = known?.alarms ?: api.getAlarms() // the library shares/retries concurrent reads
                val plan = AlarmPool.plan(pool, AlarmPool.loadedIds(context), now)
                val wanted = AlarmPool.toWatchAlarms(plan, pool, watch, now)
                if (!AlarmPool.sameOnWatch(wanted, watch)) {
                    Timber.i("H5600 alarms differ:\n watch =${watch.map { "${it.hour}:${it.minute} on=${it.enabled} sz=${it.snooze} 1x=${it.oneTime} ch=${it.hasHourlyChime}" }}\n wanted=${wanted.map { "${it.hour}:${it.minute} on=${it.enabled} sz=${it.snooze} 1x=${it.oneTime} ch=${it.hasHourlyChime}" }}")
                    api.setAlarms(ArrayList(wanted))
                    notes += "watch alarms: " + wanted.take(AlarmPool.SLOTS)
                        .joinToString(" ") { if (it.enabled) AppTimeFormat.hm(it.hour, it.minute) else "–" }
                }
                AlarmPool.saveLoadedIds(context, plan)

                // Smart-alarm slot (0x50): the next smart alarm within 24 h, or off.
                val smartId = AlarmPool.planSmart(pool, now)
                val smart = pool.firstOrNull { it.id == smartId }
                val current = if (known != null) known.smart else api.getH5600SmartAlarm()
                var smartNow = current
                if (current != null) {
                    // Watch snooze off: the phone snoozes it from the target time (a normal slot).
                    val wantedSmart = smart?.let { SmartAlarm(it.hour, it.minute, true, false, it.smartWindowMin) }
                        ?: current.copy(enabled = false)
                    if (wantedSmart != current) {
                        api.setH5600SmartAlarm(wantedSmart)
                        smartNow = wantedSmart
                        notes += "smart alarm: " + (smart?.let { AppTimeFormat.hm(it.hour, it.minute) + " (−${it.smartWindowMin} min)" } ?: "off")
                    }
                    AlarmPool.saveLoadedSmartId(context, smartId)
                }

                // The user's own mode, except while an alarm borrows the watch (a few minutes before it
                // until it and its snooze are over); then back to the user's mode.
                val base = AlarmPool.baseAt(H5600Prefs.baseSoundMode(context), H5600Prefs.nightDnd(context), now)
                val desired = AlarmPool.desiredSoundMode(pool, now, base)
                // Known and already right: no read. Otherwise one read, and a write only if different.
                val before = if (known?.sound == desired) desired else api.setSoundMode(desired)
                if (before != desired) notes += "sound ${before ?: "?"} → $desired"
                org.avmedia.gshockGoogleSync.utils.LastKnown.put(context, "sound_mode", desired.code) // Custom tab shows it
                soundModeSet.value = desired
                learnt = WatchState(ArrayList(wanted), smartNow, desired, if (known != null) known.at else now)
            }
        }
        watchState = learnt
        showSnoozing(AlarmPool.snoozing(pool, now))
        onPoolChanged?.invoke() // Alarms tab: on-watch tags, snooze buttons, one-time alarms switched off
        return ok != null
    }

    /** Hourly, not overnight: weather fetch (option on) or plain sun-data re-send (option off). */
    private suspend fun applyPeriodicLocked(notes: MutableList<String>) {
        // Not while an alarm or a snooze repeat rings; the pass a minute later catches up.
        if (AlarmPool.load(context)?.let { AlarmPool.ringing(it, LocalDateTime.now()) } == true) return
        if (SyncPacing.isQuiet(LocalDateTime.now())) return // battery: nothing periodic overnight
        refreshTempSunLocked(force = false)?.let { notes += it }
        resendSunIfDue()?.let { notes += it }
    }

    /** Dynamic sunset-step toggle: Sun data from 4 h before sunset to 10 min after, Steps otherwise. */
    private suspend fun applySunFaceLocked(notes: MutableList<String>) {
        if (!SunFace.isEnabled(context)) return
        watchCall("sun face", notes) {
            val loc = LocationProvider.getLocation(context) ?: error("no location")
            val home = HomeWifi.atHome(context)
            val desired = SunFace.desiredFace(LocalDateTime.now(), loc.latitude, loc.longitude, ZoneId.systemDefault(), SunFace.leadMin(context), home)
            val before = withTimeout(15_000) { api.setWatchFaceItem(desired) } // no write if already equal
            if (before != desired) notes += "face ${before ?: "?"} → $desired"
        }
    }

    /**
     * Runs a watch call, logging failures (and noting them in [notes]) instead of throwing; real
     * cancellation (session closed, screen left) is rethrown so nothing runs on a dead connection.
     */
    private suspend fun <T> watchCall(what: String, notes: MutableList<String>? = null, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Timber.e(e, "H5600 $what timed out"); notes?.add("$what failed (timeout)"); null
        } catch (e: Exception) {
            Timber.e(e, "H5600 $what failed"); notes?.add("$what failed (${e.message})"); null
        }

    /** Next alarm-related boundary: after every ring and snooze repeat, smart windows, sound windows. */
    private fun nextAlarmBoundary(now: LocalDateTime): LocalDateTime? {
        val pool = AlarmPool.load(context)?.filter { it.enabled }.orEmpty()
        // sound mode: start and end of each alarm's sound window
        return (listOfNotNull(
            AlarmPool.nextBoundary(pool, now), // slot reloads: a minute after each ring / repeat
            AlarmPool.nextSoundBoundary(pool, now),
            AlarmPool.nextNightBoundary(H5600Prefs.nightDnd(context), now), // do not disturb: night edges
        )).minOrNull()
    }

    private fun nextFaceSwitch(now: LocalDateTime): LocalDateTime? =
        if (!SunFace.isEnabled(context)) null
        else LocationProvider.getLocation(context)?.let {
            SunFace.nextSwitch(now, it.latitude, it.longitude, ZoneId.systemDefault(), SunFace.leadMin(context))
        }

    /**
     * For a Ready session: while connected, run each part only when it is due — alarms at alarm
     * boundaries, the face at sunset-window edges, the sun/weather refresh hourly (not overnight) —
     * instead of re-reading everything on a fixed timer.
     */
    suspend fun applyAtBoundariesWhileConnected(session: Session) = coroutineScope {
        val ready = session.state.first { it.phase is SessionPhase.Ready || it.phase is SessionPhase.Closed }
        if (ready.phase !is SessionPhase.Ready) return@coroutineScope
        val loop = launch {
            while (true) {
                val now = LocalDateTime.now()
                // Re-evaluated every round (settings may change); idle re-check every 30 min.
                val alarmAt = nextAlarmBoundary(now)
                val faceAt = nextFaceSwitch(now)
                val tickAt = TempSun.nextFetch(now)
                val next = listOfNotNull(alarmAt, faceAt, tickAt).minOrNull() ?: now.plusMinutes(30)
                val sleepMs = Duration.between(now, next).toMillis().coerceAtMost(30 * 60_000L) + 5_000
                // The exact alarm wakes us on time even in Doze; the timer below is only a backup.
                SchedulerWakeup.schedule(context, now.plusNanos(sleepMs * 1_000_000))
                // Woken early when alarms change: re-plan without running anything that is not due.
                kotlinx.coroutines.withTimeoutOrNull(sleepMs) { wake.receive() }
                val woke = LocalDateTime.now()
                SchedulerWakeup.awake(context) { mutex.withLock {
                    if (!api.isH5600SettingsSupported()) return@withLock
                    val notes = mutableListOf<String>()
                    if (alarmAt != null && !woke.isBefore(alarmAt)) onAlarmPass(applyAlarmsLocked(notes, planned = true))
                    applyPeriodicLocked(notes) // each part checks its own due time
                    if (faceAt != null && !woke.isBefore(faceAt)) applySunFaceLocked(notes)
                    if (notes.isNotEmpty()) record(notes)
                } }
            }
        }
        session.state.first { it.phase is SessionPhase.Closed }
        loop.cancel()
        watchState = null
        SchedulerWakeup.cancel(context) // nothing to do on the watch until it reconnects
    }
}

/** A planned pass trusts the known watch state for at most this long, then reads the watch again. */
private const val WATCH_STATE_MAX_AGE_H = 6L
