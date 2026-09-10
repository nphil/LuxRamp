package dev.nphil.luxramp.service

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.nphil.luxramp.LuxRampApp
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.data.PreferencesRepository
import dev.nphil.luxramp.engine.Brightness
import dev.nphil.luxramp.ui.mini.MiniActions
import dev.nphil.luxramp.ui.mini.MiniPanel
import dev.nphil.luxramp.ui.mini.MiniUiState
import dev.nphil.luxramp.ui.theme.LuxRampTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The floating panel's window: everything about the overlay that is not Compose.
 *
 * It belongs to the service rather than to an activity, so nothing here can lean on an activity
 * for the view tree owners, for a theme, or for somewhere to keep its position. [show] and [hide]
 * are the whole public surface and both are idempotent, because the service reconciles them from a
 * preference flow that re-emits for reasons that have nothing to do with this window.
 *
 * Main thread only: it drives a [LifecycleRegistry] and a view hierarchy.
 */
class MiniWindowHost(context: Context) {

    private val appContext: Context = context.applicationContext
    private val container = (appContext as LuxRampApp).container

    /**
     * A window context, not the application context: it carries the display and the configuration
     * the overlay is laid out against, which is what makes [WindowManager] report metrics worth
     * clamping against and lets Compose see a rotation.
     */
    private val windowContext: Context = run {
        val display = appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null) {
            appContext
        } else {
            appContext.createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null,
            )
        }
    }

    private val windowManager: WindowManager? =
        windowContext.getSystemService(WindowManager::class.java)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Never focusable: the panel must not take the keyboard, the back gesture or the IME away
        // from whatever the user is actually doing. Hardware acceleration is not implied for a
        // window an app adds itself, and without it every frame of the fade is a CPU redraw.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    /** Position is kept in floats so a slow drag is not rounded away one delta at a time. */
    private var positionX = 0f
    private var positionY = 0f

    /** Display bounds, cached: a drag asks for them every frame and they change only on layout. */
    private var boundsWidth = 0
    private var boundsHeight = 0

    private var view: ComposeView? = null
    private var owner: MiniViewTreeOwner? = null

    /** Set until the panel has been measured once and can be parked in its default corner. */
    private var placeInCorner = false

    /** True from [show] until [hide], including while the first placement read is in flight. */
    private var wanted = false

    fun show() {
        if (wanted) return
        if (!Settings.canDrawOverlays(appContext)) return
        wanted = true
        // The stored position has to be known before the window is added or it lands in the wrong
        // corner and jumps. A DataStore read is not something to block the main thread on, so the
        // attach waits for it instead.
        container.appScope.launch {
            val stored = runCatching { container.prefs.prefs.first() }.getOrElse { Prefs() }
            withContext(Dispatchers.Main.immediate) {
                if (wanted) attach(stored)
            }
        }
    }

    fun hide() {
        wanted = false
        detach()
    }

    fun isShowing(): Boolean = view != null

    private fun attach(initial: Prefs) {
        if (view != null) return
        val manager = windowManager ?: run {
            wanted = false
            return
        }

        val treeOwner = MiniViewTreeOwner()
        val host = ComposeView(windowContext)
        // There is no activity above this view, so the owners Compose needs for its lifecycle and
        // for saveable state have to be supplied here or the composition refuses to start.
        host.setViewTreeLifecycleOwner(treeOwner)
        host.setViewTreeSavedStateRegistryOwner(treeOwner)
        host.setContent { MiniContent(initial) }
        host.addOnLayoutChangeListener(layoutListener)

        refreshBounds()
        placeInCorner = initial.miniX < 0 || initial.miniY < 0
        if (placeInCorner) {
            // The panel wraps its content, so its size is unknown until it has composed once.
            // Asking for the far corner puts the first frame flush against the bottom right, since
            // the window manager pulls an off-screen window back in, and the first layout pass
            // then adds the inset once a real size exists.
            positionX = boundsWidth.toFloat()
            positionY = boundsHeight.toFloat()
        } else {
            positionX = initial.miniX.toFloat()
            positionY = initial.miniY.toFloat()
        }
        params.x = positionX.roundToInt()
        params.y = positionY.roundToInt()

        // The composition starts as soon as the view is attached and it collects flows that need a
        // started lifecycle, so the owner is resumed first.
        treeOwner.resume()
        val added = runCatching { manager.addView(host, params) }
        if (added.isFailure) {
            // Overlay permission revoked between the check and here, or the token was refused.
            treeOwner.destroy()
            wanted = false
            return
        }
        view = host
        owner = treeOwner
    }

    private fun detach() {
        val host = view ?: return
        view = null
        // Immediate rather than posted: the composition reads the lifecycle registry, so it has to
        // be gone before that registry is destroyed.
        runCatching { windowManager?.removeViewImmediate(host) }
        owner?.destroy()
        owner = null
    }

    /**
     * Layout is the only moment the panel's real size is known: the first pass parks it in its
     * default corner, and a later one (the panel animates its own size when it collapses, and a
     * rotation moves the edges) can leave it hanging off the screen. The move is posted because
     * changing the layout params from inside a layout pass is a request to lay out during layout.
     */
    private val layoutListener =
        View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            refreshBounds()
            val inset = DEFAULT_INSET_DP * v.resources.displayMetrics.density
            if (placeInCorner) {
                placeInCorner = false
                val x = boundsWidth - v.width - inset
                val y = boundsHeight - v.height - inset
                v.post {
                    moveTo(x, y)
                    persistPosition()
                }
            } else if (positionX > boundsWidth - v.width || positionY > boundsHeight - v.height) {
                // Only when it would actually move: a collapse animation is a layout per frame.
                v.post { moveTo(positionX, positionY) }
            }
        }

    /** Incremental pixel deltas from the panel's drag handle. */
    private fun moveBy(dx: Float, dy: Float) {
        moveTo(positionX + dx, positionY + dy)
    }

    private fun moveTo(x: Float, y: Float) {
        val host = view ?: return
        // Before the first layout the panel measures zero and clamping against that would pin it
        // to the origin; until then the window manager is what keeps it on screen.
        val maxX = (boundsWidth - host.width).coerceAtLeast(0).toFloat()
        val maxY = (boundsHeight - host.height).coerceAtLeast(0).toFloat()
        positionX = x.coerceIn(0f, maxX)
        positionY = y.coerceIn(0f, maxY)
        val nextX = positionX.roundToInt()
        val nextY = positionY.roundToInt()
        if (nextX == params.x && nextY == params.y) return
        params.x = nextX
        params.y = nextY
        runCatching { windowManager?.updateViewLayout(host, params) }
    }

    private fun persistPosition() {
        val x = params.x
        val y = params.y
        container.appScope.launch { runCatching { container.prefs.setMiniPosition(x, y) } }
    }

    private fun refreshBounds() {
        val bounds = windowManager?.currentWindowMetrics?.bounds ?: return
        boundsWidth = bounds.width()
        boundsHeight = bounds.height()
    }

    private fun openApp() {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return
        // Starting an activity from the background is allowed here precisely because we hold the
        // overlay permission; without it this window would not exist either.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(launch) }
    }

    /** The linear brightness the settings provider holds, all we have while the loop is stopped. */
    private fun readSettingBrightness(): Float {
        val value = runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
        }.getOrDefault(0)
        return Brightness.fromSetting(value)
    }

    @Composable
    private fun MiniContent(initial: Prefs) {
        val prefs by container.prefs.prefs.collectAsStateWithLifecycle(initial)
        val telemetry by container.controller.telemetry.collectAsStateWithLifecycle()

        // With automatic control off the controller publishes nothing, so the slider follows the
        // settings provider instead. Registered only in that case: while the loop runs, this would
        // fire on every write we make ourselves.
        val manual = remember { mutableFloatStateOf(0f) }
        val autoEnabled = prefs.enabled
        DisposableEffect(autoEnabled) {
            if (autoEnabled) return@DisposableEffect onDispose { }
            manual.floatValue = readSettingBrightness()
            val resolver = appContext.contentResolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    manual.floatValue = readSettingBrightness()
                }
            }
            runCatching {
                resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    observer,
                )
            }
            onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
        }

        val linear = if (autoEnabled) telemetry.currentLinear else manual.floatValue
        val state = MiniUiState(
            brightnessPercent = Brightness.toPercent(linear),
            offset = prefs.offset,
            autoEnabled = autoEnabled,
            collapsed = prefs.miniCollapsed,
            writerLabel = telemetry.writerLabel,
            screenOn = telemetry.screenOn,
            idleAlpha = if (prefs.miniFadeEnabled) prefs.miniIdleAlpha else 1f,
            fadeDelayMillis = prefs.miniFadeDelayMillis,
        )
        // Every action closes over the host and nothing else, so one instance outlives every
        // recomposition and the panel stays skippable.
        val actions = remember {
            MiniActions(
                onBrightnessDrag = { percent ->
                    container.controller.setUserBrightness(Brightness.fromPercent(percent))
                },
                onBrightnessCommit = { percent ->
                    container.controller.setUserBrightness(Brightness.fromPercent(percent))
                },
                // An offset only means anything once it is stored, because the loop reads it back
                // from preferences: the drag is the panel's own state until the finger lifts.
                onOffsetDrag = { },
                onOffsetCommit = { offset -> edit { update { it.copy(offset = offset) } } },
                onToggleAuto = { enabled -> edit { setEnabled(enabled) } },
                onCollapse = { collapsed -> edit { setMiniCollapsed(collapsed) } },
                onOpenApp = { openApp() },
                onClose = { edit { setMiniEnabled(false) } },
                onHandleDrag = { dx, dy -> moveBy(dx, dy) },
                onHandleDragEnd = { persistPosition() },
            )
        }

        LuxRampTheme(prefs.themeMode, prefs.dynamicColor, prefs.themeId) {
            MiniPanel(state = state, actions = actions)
        }
    }

    /**
     * Preference writes from the panel run on the app scope, never on the composition: the window
     * is often closing itself with the very edit it is making.
     */
    private fun edit(block: suspend PreferencesRepository.() -> Unit) {
        container.appScope.launch { runCatching { container.prefs.block() } }
    }

    /**
     * The view tree owners an activity would otherwise provide.
     *
     * Compose will not compose without a lifecycle, and its saveable state registry wants an owner
     * even where nothing is ever restored, so the registry is attached and restored from nothing
     * exactly once, before anything can move the lifecycle past INITIALIZED.
     */
    private class MiniViewTreeOwner : SavedStateRegistryOwner {

        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performAttach()
            savedState.performRestore(null)
        }

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        /** Inset from the screen edge the panel is parked at the first time it appears. */
        const val DEFAULT_INSET_DP = 16f
    }
}
