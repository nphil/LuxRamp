package dev.nphil.luxramp.control

import android.content.ContentResolver
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import dev.nphil.luxramp.engine.Brightness
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Shown while instant writes are available. */
const val LABEL_TEMPORARY = "Instant (Shizuku)"

/** Shown while brightness goes through the settings provider and the platform ramps it. */
const val LABEL_SETTINGS = "System ramp (fallback)"

/** Shizuku is authorised, but this ROM's DisplayManager does not expose the transaction. */
const val LABEL_SHIZUKU_MISSING = "System ramp (Shizuku API missing)"

/** The privileged write was refused at runtime; we gave up on it for this session. */
const val LABEL_SHIZUKU_FAILED = "System ramp (Shizuku write failed)"

/**
 * One way of putting a linear brightness on the screen.
 *
 * [write] returns false for "this mechanism did not work" — never throws — so the controller can
 * degrade to the next mechanism without unwinding its own state.
 */
interface BrightnessWriter {
    val label: String

    fun write(linear: Float): Boolean

    fun close() {}
}

/**
 * Writes `Settings.System.SCREEN_BRIGHTNESS`.
 *
 * Always available (given the WRITE_SETTINGS app-op), but the platform animates towards the value
 * on its own schedule, so it is the fallback rather than the mechanism we want.
 */
class SettingsBrightnessWriter(context: Context) : BrightnessWriter {

    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override val label: String = LABEL_SETTINGS

    override fun write(linear: Float): Boolean {
        if (!Settings.System.canWrite(appContext)) return false
        val value = Brightness.toSetting(linear.coerceIn(Brightness.MIN, 1f))
        return runCatching {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }.getOrDefault(false)
    }
}

/**
 * Writes brightness straight into DisplayManagerService through Shizuku's privileged binder.
 *
 * `setTemporaryBrightness` is what the platform's own auto-brightness controller uses: it takes
 * effect on the next frame with no animation and no settings-provider round trip, which is what
 * lets us own the ramp shape. It is a hidden `@SystemApi`, so the transaction code is read off
 * `IDisplayManager$Stub` reflectively; a ROM that renamed or dropped it makes the constructor
 * throw, and the controller stays on [SettingsBrightnessWriter].
 */
class TemporaryBrightnessWriter(private val displayId: Int = 0) : BrightnessWriter {

    private val transactionCode: Int
    private val binder: IBinder

    init {
        // The reflective field read below touches a blocklisted class on some ROMs.
        exemptDisplayApis()
        val code = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getDeclaredField("TRANSACTION_setTemporaryBrightness")
            .apply { isAccessible = true }
            .getInt(null)
        val service = SystemServiceHelper.getSystemService(DISPLAY_SERVICE)
            ?: throw IllegalStateException("Shizuku did not hand out the display service binder")
        transactionCode = code
        binder = ShizukuBinderWrapper(service)
    }

    override val label: String = LABEL_TEMPORARY

    override fun write(linear: Float): Boolean {
        val value = linear.coerceIn(Brightness.MIN, 1f)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(displayId)
            data.writeFloat(value)
            val delivered = binder.transact(transactionCode, data, reply, 0)
            reply.readException()
            delivered
        } catch (_: Throwable) {
            // SecurityException (permission revoked), RemoteException (binder died), or anything
            // else the far side raises: the mechanism is gone, so report failure and let the
            // controller fall back.
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val DESCRIPTOR = "android.hardware.display.IDisplayManager"
        const val DISPLAY_SERVICE = "display"

        @Volatile
        private var exempted = false

        /** Lifts the hidden-API blocklist for the display package, once per process. */
        @Synchronized
        fun exemptDisplayApis() {
            if (exempted) return
            exempted = true
            runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/display/") }
        }
    }
}
