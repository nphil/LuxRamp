package dev.nphil.luxramp

import android.app.Application

/**
 * Process entry point. The container is built lazily so that a cold start for the boot receiver
 * pays for the sensor stack only once the service actually asks for it.
 */
class LuxRampApp : Application() {
    val container by lazy { AppContainer(this) }
}
