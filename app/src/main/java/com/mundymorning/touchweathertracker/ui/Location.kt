package com.mundymorning.touchweathertracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One-shot device location using the platform LocationManager (no Google Play services needed). Only called after the user taps "Use my location". */
object LocationHelper {
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        // Prefer a recent last-known fix (< 5 min) to save battery.
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.filter { System.currentTimeMillis() - it.time < 5 * 60_000L }.maxByOrNull { it.time }?.let { return it }
        for (p in providers) {
            val loc = suspendCancellableCoroutine<Location?> { cont ->
                val cs = CancellationSignal()
                cont.invokeOnCancellation { cs.cancel() }
                runCatching { LocationManagerCompat.getCurrentLocation(lm, p, cs, Executors.newSingleThreadExecutor()) { cont.resume(it) } }.onFailure { cont.resume(null) }
            }
            if (loc != null) return loc
        }
        return null
    }
}
