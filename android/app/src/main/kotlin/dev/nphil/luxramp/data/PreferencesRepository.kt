package dev.nphil.luxramp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.luxRampStore: DataStore<Preferences> by preferencesDataStore("luxramp")

private val KEY_ENABLED = booleanPreferencesKey("enabled")
private val KEY_RAMP_UP = longPreferencesKey("ramp_up_millis")
private val KEY_RAMP_DOWN = longPreferencesKey("ramp_down_millis")
private val KEY_TAU_UP = longPreferencesKey("tau_up_millis")
private val KEY_TAU_DOWN = longPreferencesKey("tau_down_millis")
private val KEY_OFFSET = floatPreferencesKey("offset")
private val KEY_DEADBAND = floatPreferencesKey("deadband_ratio")
private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
private val KEY_THEME_ID = stringPreferencesKey("theme_id")
private val KEY_MINI_ENABLED = booleanPreferencesKey("mini_enabled")
private val KEY_MINI_FADE = booleanPreferencesKey("mini_fade_enabled")
private val KEY_MINI_FADE_DELAY = longPreferencesKey("mini_fade_delay_millis")
private val KEY_MINI_IDLE_ALPHA = floatPreferencesKey("mini_idle_alpha")
private val KEY_MINI_X = intPreferencesKey("mini_x")
private val KEY_MINI_Y = intPreferencesKey("mini_y")
private val KEY_MINI_COLLAPSED = booleanPreferencesKey("mini_collapsed")

/**
 * The single writer of the `luxramp` DataStore.
 *
 * Three entry points into the process read it: the UI edits the tuning, the
 * floating window edits the same values from outside the app, and the controller
 * both observes it and writes [setOffset] back when the user drags the system
 * brightness slider. Every write goes through DataStore's own serialisation, so
 * they never clobber each other.
 */
class PreferencesRepository(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.luxRampStore

    /**
     * A read failure surfaces as an [IOException] *in the stream*, which would otherwise cancel
     * every collector - the controller's included, taking the ramp down with it. An unreadable file
     * means "no preferences yet", so fall back to the defaults and keep the loop alive.
     */
    val prefs: Flow<Prefs> = store.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { it.toPrefs() }

    /**
     * Read-modify-write of the whole record, applied inside DataStore's transaction so a slider
     * commit racing an offset write from the controller cannot drop either change.
     */
    suspend fun update(transform: (Prefs) -> Prefs) {
        store.edit { mutable -> mutable.write(transform(mutable.toPrefs())) }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setOffset(offset: Float) {
        store.edit { it[KEY_OFFSET] = offset }
    }

    suspend fun setOnboarded(onboarded: Boolean) {
        store.edit { it[KEY_ONBOARDED] = onboarded }
    }

    suspend fun setMiniEnabled(enabled: Boolean) {
        store.edit { it[KEY_MINI_ENABLED] = enabled }
    }

    suspend fun setMiniCollapsed(collapsed: Boolean) {
        store.edit { it[KEY_MINI_COLLAPSED] = collapsed }
    }

    /** Position is written on every drag release, so it is its own narrow edit. */
    suspend fun setMiniPosition(x: Int, y: Int) {
        store.edit {
            it[KEY_MINI_X] = x
            it[KEY_MINI_Y] = y
        }
    }
}

private fun Preferences.toPrefs(): Prefs {
    val defaults = Prefs()
    return Prefs(
        enabled = this[KEY_ENABLED] ?: defaults.enabled,
        rampUpMillis = this[KEY_RAMP_UP] ?: defaults.rampUpMillis,
        rampDownMillis = this[KEY_RAMP_DOWN] ?: defaults.rampDownMillis,
        tauUpMillis = this[KEY_TAU_UP] ?: defaults.tauUpMillis,
        tauDownMillis = this[KEY_TAU_DOWN] ?: defaults.tauDownMillis,
        offset = this[KEY_OFFSET] ?: defaults.offset,
        deadbandRatio = this[KEY_DEADBAND] ?: defaults.deadbandRatio,
        onboarded = this[KEY_ONBOARDED] ?: defaults.onboarded,
        themeMode = this[KEY_THEME_MODE]?.toThemeMode() ?: defaults.themeMode,
        dynamicColor = this[KEY_DYNAMIC_COLOR] ?: defaults.dynamicColor,
        themeId = this[KEY_THEME_ID] ?: defaults.themeId,
        miniEnabled = this[KEY_MINI_ENABLED] ?: defaults.miniEnabled,
        miniFadeEnabled = this[KEY_MINI_FADE] ?: defaults.miniFadeEnabled,
        miniFadeDelayMillis = this[KEY_MINI_FADE_DELAY] ?: defaults.miniFadeDelayMillis,
        miniIdleAlpha = this[KEY_MINI_IDLE_ALPHA] ?: defaults.miniIdleAlpha,
        miniX = this[KEY_MINI_X] ?: defaults.miniX,
        miniY = this[KEY_MINI_Y] ?: defaults.miniY,
        miniCollapsed = this[KEY_MINI_COLLAPSED] ?: defaults.miniCollapsed,
    )
}

/** An unknown stored name means a downgrade, not corruption: fall back rather than crash. */
private fun String.toThemeMode(): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.SYSTEM

private fun MutablePreferences.write(prefs: Prefs) {
    this[KEY_ENABLED] = prefs.enabled
    this[KEY_RAMP_UP] = prefs.rampUpMillis
    this[KEY_RAMP_DOWN] = prefs.rampDownMillis
    this[KEY_TAU_UP] = prefs.tauUpMillis
    this[KEY_TAU_DOWN] = prefs.tauDownMillis
    this[KEY_OFFSET] = prefs.offset
    this[KEY_DEADBAND] = prefs.deadbandRatio
    this[KEY_ONBOARDED] = prefs.onboarded
    this[KEY_THEME_MODE] = prefs.themeMode.name
    this[KEY_DYNAMIC_COLOR] = prefs.dynamicColor
    this[KEY_THEME_ID] = prefs.themeId
    this[KEY_MINI_ENABLED] = prefs.miniEnabled
    this[KEY_MINI_FADE] = prefs.miniFadeEnabled
    this[KEY_MINI_FADE_DELAY] = prefs.miniFadeDelayMillis
    this[KEY_MINI_IDLE_ALPHA] = prefs.miniIdleAlpha
    this[KEY_MINI_X] = prefs.miniX
    this[KEY_MINI_Y] = prefs.miniY
    this[KEY_MINI_COLLAPSED] = prefs.miniCollapsed
}
