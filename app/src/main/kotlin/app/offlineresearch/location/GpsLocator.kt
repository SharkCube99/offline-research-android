package app.offlineresearch.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.offlineresearch.rag.Position
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's position from its satellite receiver. Nothing here uses a
 * network: the receiver only listens, which is why it works in airplane mode,
 * and the app has no permission to send the position anywhere. Without the
 * network's help the first fix after a long pause can take a minute or more
 * and needs a view of the sky.
 */
class GpsLocator(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A recent position if the phone has one, else a fresh fix; null without permission or within [timeoutMs]. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun current(timeoutMs: Long = FIX_TIMEOUT_MS): Position? {
        if (!hasPermission()) return null
        try {
            recent()?.let { return it }
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.w(TAG, "location is switched off in the phone's settings")
                return null
            }
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(Position(location.latitude, location.longitude))
                        }

                        // Empty on purpose: older Android versions call these and crash on a missing override.
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    }
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                }
            }
        } catch (e: SecurityException) {
            return null // permission withdrawn while asking
        } catch (e: IllegalArgumentException) {
            return null // the phone has no satellite receiver
        }
    }

    /** The newest position any provider already holds, if it is fresh enough to trust. */
    @SuppressLint("MissingPermission")
    private fun recent(): Position? = manager.getProviders(true)
        .mapNotNull { manager.getLastKnownLocation(it) }
        .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < RECENT_NS }
        .maxByOrNull { it.elapsedRealtimeNanos }
        ?.let { Position(it.latitude, it.longitude) }

    companion object {
        private const val TAG = "OfflineResearch"
        const val FIX_TIMEOUT_MS = 90_000L
        private const val RECENT_NS = 15L * 60 * 1_000_000_000
    }
}
