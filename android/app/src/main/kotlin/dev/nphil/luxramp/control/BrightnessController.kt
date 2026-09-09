package dev.nphil.luxramp.control

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.data.PreferencesRepository
import dev.nphil.luxramp.engine.Brightness
import dev.nphil.luxramp.engine.BrightnessCurve
import dev.nphil.luxramp.engine.LuxFilter
import dev.nphil.luxramp.engine.Ramper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln

/** One point of the live trace: monotonic [timeMillis] from `SystemClock.elapsedRealtime()`. */
data class Sample(val timeMillis: Long, val lux: Float, val linear: Float)

/** Everything the UI and the notification need to describe what the controller is doing. */
data class Telemetry(
    val rawLux: Float = 0f,
    val filteredLux: Float = 0f,
    val targetLinear: Float = 0f,
    val currentLinear: Float = 0f,
    val writerLabel: String = LABEL_SETTINGS,
    val screenOn: Boolean = true,
    val history: List<Sample> = emptyList(),
)

/**
 * Drives the display brightness from the ambient light sensor.
 *
 * Every piece of mutable state below is touched by exactly one coroutine: producers (sensor
 * callback, screen broadcast, settings observer, preference flow, Shizuku flow, ramp ticker) only
 * post an [Event] onto [events], and the consumer started by [start] is the single writer. That is
 * what makes a 60 Hz ramp, live preference edits and a user grabbing the system slider safe to mix
 * without a lock anywhere in the hot path.
 */
class BrightnessController(
    context: Context,
    private val prefs: PreferencesRepository,
    private val gateway: ShizukuGateway,
    private val scope: CoroutineScope,
) {

    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val sensorManager: SensorManager? = appContext.getSystemService(SensorManager::class.java)
    private val powerManager: PowerManager? = appContext.getSystemService(PowerManager::class.java)
    private val lightSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val events = Channel<Event>(Channel.UNLIMITED)

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val curve = BrightnessCurve.STOCK
    private var settings: Prefs = Prefs()
    private val filter = LuxFilter(settings.tauUpMillis, settings.tauDownMillis, settings.deadbandRatio)
    private val ramper = Ramper(settings.rampUpMillis, settings.rampDownMillis)

    private var writer: BrightnessWriter? = null
    private var usingTemporary = false
    private var temporaryBlocked = false
    private var writerLabel = LABEL_SETTINGS

    private var rawLux = Float.NaN

    /** Filtered lux at the last retarget; the deadband is measured against this, not the target. */
    private var lastActedLux = Float.NaN
    private var screenOn = true
    private var primeNext = true

    /** `screen_brightness_mode` as it was before we forced manual, or -1 if we never changed it. */
    private var savedMode = -1

    private val selfWrites = IntArray(SELF_WRITE_RING) { -1 }
    private var selfWriteCursor = 0
    private var lastSelfWriteAt = 0L
    private var lastSettingsWriteAt = 0L
    private var lastObservedUserValue = -1

    /** The int the settings provider holds as far as we know, or -1 if we have not looked. */
    private var lastWrittenSetting = -1

    private val history = ArrayDeque<Sample>()
    private var historySnapshot: List<Sample> = emptyList()
    private var lastSampleAt = 0L

    /** Read by the consumer coroutine, written by [start]/[stop] on whatever thread calls them. */
    @Volatile
    private var running = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var observer: ContentObserver? = null
    private var receiverRegistered = false
    private var sensorRegistered = false

    private var consumerJob: Job? = null
    private var prefsJob: Job? = null
    private var shizukuJob: Job? = null
    private var tickerJob: Job? = null
    private var commitJob: Job? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.isEmpty()) return
            events.trySend(Event.Lux(event.values[0], SystemClock.elapsedRealtime()))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> events.trySend(Event.Screen(true))
                Intent.ACTION_SCREEN_OFF -> events.trySend(Event.Screen(false))
            }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true

        val thread = HandlerThread("luxramp-control").also { it.start() }
        val threadHandler = Handler(thread.looper)
        handlerThread = thread
        handler = threadHandler

        // Events queued by a previous run must not be replayed against fresh state.
        while (events.tryReceive().isSuccess) Unit

        captureAndDisableAutoBrightness()
        if (writer == null) useSettingsWriter(LABEL_SETTINGS)

        screenOn = powerManager?.isInteractive ?: true
        primeNext = true
        filter.reset()
        lastActedLux = Float.NaN
        rawLux = Float.NaN
        lastObservedUserValue = -1
        // Start from what the provider actually holds, so a brightness that is already right
        // costs no write at all.
        lastWrittenSetting = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        }.getOrDefault(-1)

        consumerJob = scope.launch(Dispatchers.Default) {
            while (true) {
                handle(events.receive())
            }
        }
        prefsJob = prefs.prefs
            .onEach { events.trySend(Event.Preferences(it)) }
            .launchIn(scope)
        shizukuJob = gateway.state
            .onEach { events.trySend(Event.Shizuku(it is ShizukuState.Ready)) }
            .launchIn(scope)

        val brightnessObserver = object : ContentObserver(threadHandler) {
            override fun onChange(selfChange: Boolean) {
                val value = runCatching {
                    Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                }.getOrDefault(-1)
                if (value >= 0) events.trySend(Event.SettingChanged(value))
            }
        }
        observer = brightnessObserver
        runCatching {
            resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                brightnessObserver,
            )
        }

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            // Screen on/off are protected system broadcasts; NOT_EXPORTED is still the honest
            // declaration, and the Handler keeps the callback off the main thread.
            appContext.registerReceiver(
                screenReceiver,
                screenFilter,
                null,
                threadHandler,
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess { receiverRegistered = true }

        if (screenOn) registerSensor()
        publish()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false

        consumerJob?.cancel()
        consumerJob = null
        prefsJob?.cancel()
        prefsJob = null
        shizukuJob?.cancel()
        shizukuJob = null
        stopTicker()
        commitJob?.cancel()
        commitJob = null

        unregisterSensor()
        if (receiverRegistered) {
            receiverRegistered = false
            runCatching { appContext.unregisterReceiver(screenReceiver) }
        }
        observer?.let { registered -> runCatching { resolver.unregisterContentObserver(registered) } }
        observer = null

        writer?.let { active -> runCatching { active.close() } }
        writer = null
        usingTemporary = false
        temporaryBlocked = false
        writerLabel = LABEL_SETTINGS

        restoreAutoBrightness()

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        filter.reset()
        lastActedLux = Float.NaN
        rawLux = Float.NaN
        primeNext = true
        lastWrittenSetting = -1
        lastObservedUserValue = -1
        history.clear()
        historySnapshot = emptyList()
        lastSampleAt = 0L
        _telemetry.value = Telemetry(writerLabel = writerLabel, screenOn = screenOn)
    }

    private fun handle(event: Event) {
        // An event already in flight when stop() ran must not resurrect any of the state above.
        if (!running) return
        when (event) {
            is Event.Lux -> onLux(event.lux, event.timeMillis)
            is Event.Screen -> onScreen(event.on)
            is Event.Preferences -> onPreferences(event.prefs)
            is Event.Shizuku -> onShizuku(event.ready)
            is Event.SettingChanged -> onSettingChanged(event.value)
            is Event.Tick -> onTick()
            is Event.Commit -> commit()
        }
    }

    private fun onLux(lux: Float, timeMillis: Long) {
        rawLux = lux
        val filtered = filter.submit(lux, timeMillis)
        if (filtered.isNaN()) {
            publish()
            return
        }
        if (primeNext) {
            // Screen just came on: land on the right brightness before the user can see a ramp.
            primeNext = false
            stopTicker()
            val target = targetFor(filtered)
            ramper.prime(target)
            lastActedLux = filtered
            applyBrightness(target, force = true)
            scheduleCommit()
        } else if (lastActedLux.isNaN() || !filter.settled(lastActedLux)) {
            lastActedLux = filtered
            retarget(targetFor(filtered), timeMillis)
        }
        publish()
    }

    private fun onTick() {
        if (!ramper.isRamping) {
            stopTicker()
            return
        }
        val value = ramper.step(SystemClock.elapsedRealtime())
        val finished = !ramper.isRamping
        applyBrightness(value, force = finished)
        if (finished) {
            stopTicker()
            scheduleCommit()
        }
        publish()
    }

    private fun onScreen(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        if (on) {
            filter.reset()
            lastActedLux = Float.NaN
            rawLux = Float.NaN
            primeNext = true
            registerSensor()
        } else {
            // The service stays in the foreground; there is simply nothing to measure or write.
            unregisterSensor()
            stopTicker()
            commitJob?.cancel()
            commitJob = null
        }
        publish()
    }

    private fun onPreferences(next: Prefs) {
        val previous = settings
        settings = next
        filter.tauUpMillis = next.tauUpMillis
        filter.tauDownMillis = next.tauDownMillis
        filter.deadbandRatio = next.deadbandRatio
        ramper.upMillis = next.rampUpMillis
        ramper.downMillis = next.rampDownMillis
        if (next.offset != previous.offset && !primeNext && !filter.value.isNaN()) {
            retarget(targetFor(filter.value), SystemClock.elapsedRealtime())
        }
        publish()
    }

    private fun onShizuku(ready: Boolean) {
        if (!ready) {
            // A Shizuku restart is a fresh chance for the privileged writer.
            temporaryBlocked = false
            useSettingsWriter(LABEL_SETTINGS)
            publish()
            return
        }
        if (usingTemporary || temporaryBlocked) {
            publish()
            return
        }
        val privileged = runCatching { TemporaryBrightnessWriter() }.getOrNull()
        if (privileged == null) {
            temporaryBlocked = true
            useSettingsWriter(LABEL_SHIZUKU_MISSING)
        } else {
            writer?.let { active -> runCatching { active.close() } }
            writer = privileged
            usingTemporary = true
            writerLabel = privileged.label
        }
        publish()
    }

    /**
     * A brightness we did not write means the user grabbed the system slider: adopt their value and
     * re-derive the offset, so the whole curve moves with them instead of us fighting back.
     */
    private fun onSettingChanged(value: Int) {
        if (isSelfWrite(value)) return
        if (SystemClock.elapsedRealtime() - lastSelfWriteAt < SELF_WRITE_QUIET_MILLIS) return
        if (value == lastObservedUserValue) return
        lastObservedUserValue = value
        // The provider now holds their number, not ours; remembering it (without claiming it as a
        // self write) keeps us from writing the same int straight back at them.
        lastWrittenSetting = value

        val desired = Brightness.fromSetting(value).coerceIn(Brightness.MIN, 1f)
        stopTicker()
        ramper.prime(desired)
        if (!filter.value.isNaN()) lastActedLux = filter.value
        publish()

        val derived = offsetFor(filter.value, desired) ?: return
        if (abs(derived - settings.offset) <= OFFSET_EPSILON) return
        scope.launch { runCatching { prefs.setOffset(derived) } }
    }

    private fun retarget(target: Float, nowMillis: Long) {
        if (abs(target - ramper.target) <= TARGET_EPSILON && !ramper.current.isNaN()) return
        ramper.retarget(target, nowMillis)
        if (ramper.isRamping) {
            ensureTicker()
        } else {
            applyBrightness(target, force = true)
            scheduleCommit()
        }
    }

    private fun targetFor(lux: Float): Float =
        curve.brightnessFor(lux, settings.offset).coerceIn(Brightness.MIN, 1f)

    /**
     * Solves `curve(lux, offset) == desired` for the offset.
     *
     * The curve applies the offset as Android's auto-brightness gamma, `b' = b ^ (3 ^ -offset)`,
     * so `offset = -log3(ln(b') / ln(b))`. Returns null when the algebra has no answer: at either
     * end of the range one of the logarithms is zero.
     */
    private fun offsetFor(lux: Float, desired: Float): Float? {
        if (lux.isNaN()) return null
        val base = curve.brightnessFor(lux, 0f)
        if (base <= 0f || base >= 1f) return null
        // ln(1) is 0, so the very top of the range has no solution; solving just inside it lets a
        // drag to maximum saturate the offset instead of being ignored.
        val target = desired.coerceIn(Brightness.MIN, MAX_SOLVABLE_LINEAR)
        val ratio = ln(target.toDouble()) / ln(base.toDouble())
        if (ratio <= 0.0 || !ratio.isFinite()) return null
        val offset = -(ln(ratio) / LN_3)
        if (!offset.isFinite()) return null
        return offset.toFloat().coerceIn(-1f, 1f)
    }

    private fun applyBrightness(linear: Float, force: Boolean) {
        if (linear.isNaN()) return
        val active = writer ?: return
        if (!usingTemporary) {
            // Every settings write is a provider round trip that also wakes our own observer, so
            // while ramping we write coarser steps and let the platform interpolate between them.
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastSettingsWriteAt < SETTINGS_WRITE_INTERVAL_MILLIS) return
            // The provider stores an int: a step too small to move it is pure round-trip cost.
            val setting = Brightness.toSetting(linear.coerceIn(Brightness.MIN, 1f))
            if (setting == lastWrittenSetting) return
            lastSettingsWriteAt = now
            rememberSelfWrite(setting)
        }
        if (active.write(linear)) return
        // The privileged writer failed mid-session (permission revoked, binder died): degrade once
        // and re-issue this same value through the settings provider.
        if (!usingTemporary) return
        temporaryBlocked = true
        useSettingsWriter(LABEL_SHIZUKU_FAILED)
        val fallback = writer ?: return
        lastSettingsWriteAt = SystemClock.elapsedRealtime()
        rememberSelfWrite(Brightness.toSetting(linear.coerceIn(Brightness.MIN, 1f)))
        // The value we just failed to deliver has to reach the screen some other way, so this
        // write is unconditional - no int dedup, the display never saw it.
        fallback.write(linear)
    }

    private fun useSettingsWriter(label: String) {
        writerLabel = label
        if (writer != null && !usingTemporary) return
        writer?.let { active -> runCatching { active.close() } }
        writer = SettingsBrightnessWriter(appContext)
        usingTemporary = false
    }

    private fun scheduleCommit() {
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(COMMIT_DEBOUNCE_MILLIS)
            events.trySend(Event.Commit)
        }
    }

    /**
     * Parks the settled brightness in `Settings.System.SCREEN_BRIGHTNESS` so the value survives
     * the screen going off, a reboot, or LuxRamp being stopped.
     */
    private fun commit() {
        commitJob = null
        val value = ramper.current
        if (value.isNaN()) return
        if (!Settings.System.canWrite(appContext)) return
        val setting = Brightness.toSetting(value.coerceIn(Brightness.MIN, 1f))
        if (setting == lastWrittenSetting) return
        rememberSelfWrite(setting)
        runCatching { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, setting) }
    }

    private fun rememberSelfWrite(value: Int) {
        selfWrites[selfWriteCursor] = value
        selfWriteCursor = (selfWriteCursor + 1) % selfWrites.size
        lastSelfWriteAt = SystemClock.elapsedRealtime()
        lastWrittenSetting = value
    }

    private fun isSelfWrite(value: Int): Boolean = selfWrites.any { it == value }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(RAMP_STEP_MILLIS)
                events.trySend(Event.Tick)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun registerSensor() {
        if (sensorRegistered) return
        val manager = sensorManager ?: return
        val sensor = lightSensor ?: return
        val threadHandler = handler ?: return
        sensorRegistered = runCatching {
            manager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, threadHandler)
        }.getOrDefault(false)
    }

    private fun unregisterSensor() {
        if (!sensorRegistered) return
        sensorRegistered = false
        runCatching { sensorManager?.unregisterListener(sensorListener) }
    }

    private fun captureAndDisableAutoBrightness() {
        if (!Settings.System.canWrite(appContext)) return
        savedMode = runCatching {
            Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            )
        }.getOrDefault(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        runCatching {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
    }

    private fun restoreAutoBrightness() {
        // Undo only what we changed: without WRITE_SETTINGS at start we never touched the mode,
        // and switching the user into auto behind their back would be a surprise.
        if (savedMode < 0) return
        savedMode = -1
        if (!Settings.System.canWrite(appContext)) return
        runCatching {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            )
        }
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val filtered = finite(filter.value)
        val current = finite(ramper.current)
        if (now - lastSampleAt >= SAMPLE_INTERVAL_MILLIS) {
            lastSampleAt = now
            history.addLast(Sample(now, filtered, current))
            val cutoff = now - HISTORY_WINDOW_MILLIS
            while (history.isNotEmpty() && history[0].timeMillis < cutoff) history.removeFirst()
            while (history.size > HISTORY_MAX_POINTS) history.removeFirst()
            historySnapshot = history.toList()
        }
        _telemetry.value = Telemetry(
            rawLux = finite(rawLux),
            filteredLux = filtered,
            targetLinear = finite(ramper.target),
            currentLinear = current,
            writerLabel = writerLabel,
            screenOn = screenOn,
            history = historySnapshot,
        )
    }

    private fun finite(value: Float): Float = if (value.isNaN()) 0f else value

    private sealed interface Event {
        data class Lux(val lux: Float, val timeMillis: Long) : Event
        data class Screen(val on: Boolean) : Event
        data class Preferences(val prefs: Prefs) : Event
        data class Shizuku(val ready: Boolean) : Event
        data class SettingChanged(val value: Int) : Event
        data object Tick : Event
        data object Commit : Event
    }

    private companion object {
        const val RAMP_STEP_MILLIS = 16L
        const val COMMIT_DEBOUNCE_MILLIS = 2_000L
        const val SETTINGS_WRITE_INTERVAL_MILLIS = 150L
        const val SELF_WRITE_QUIET_MILLIS = 750L
        const val SAMPLE_INTERVAL_MILLIS = 250L
        const val HISTORY_WINDOW_MILLIS = 120_000L
        const val HISTORY_MAX_POINTS = 480
        const val TARGET_EPSILON = 0.0005f
        const val OFFSET_EPSILON = 0.002f
        const val SELF_WRITE_RING = 8
        const val MAX_SOLVABLE_LINEAR = 0.999f

        val LN_3 = ln(3.0)
    }
}
