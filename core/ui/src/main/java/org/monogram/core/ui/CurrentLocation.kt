package org.monogram.core.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private fun locationPermitted(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

private fun locationManager(context: Context) =
    context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

private fun locationProviders(manager: LocationManager): List<String> =
    linkedSetOf<String>().apply {
        addAll(manager.getProviders(true))
        add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER); add(
        LocationManager.PASSIVE_PROVIDER
    )
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    }.filter {
        it == LocationManager.PASSIVE_PROVIDER || (Build.VERSION.SDK_INT >= 31 && it == LocationManager.FUSED_PROVIDER) || manager.isProviderEnabled(
            it
        )
    }

@Suppress("MissingPermission")
fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    if (!locationPermitted(context)) return null
    val manager = locationManager(context) ?: return null
    val providers = try {
        locationProviders(manager)
    } catch (_: SecurityException) {
        return null
    }
    return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }?.let { it.latitude to it.longitude }
}

@Suppress("MissingPermission")
suspend fun currentLocation(
    context: Context, timeoutMs: Long = 8_000, allowCached: Boolean = true
): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    if (!locationPermitted(context)) return@withContext null
    val manager = locationManager(context) ?: return@withContext null
    val providers = try {
        locationProviders(manager)
    } catch (_: SecurityException) {
        return@withContext null
    }
    val cached =
        if (allowCached) providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } else null
    val fresh = coroutineScope {
        providers.map { provider ->
            async {
                withTimeoutOrNull(timeoutMs) {
                    awaitSingleFix(
                        manager, provider
                    )
                }
            }
        }.mapNotNull { it.await() }
            .filter { allowCached || SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..30_000_000_000L }
            .maxByOrNull { it.time }
    }
    listOfNotNull(fresh, cached).maxByOrNull { it.time }?.let { it.latitude to it.longitude }
}

@Suppress("MissingPermission")
private suspend fun awaitSingleFix(manager: LocationManager, provider: String): Location? =
    suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean()
        val signal = if (Build.VERSION.SDK_INT >= 30) CancellationSignal() else null
        lateinit var listener: android.location.LocationListener
        fun release() {
            runCatching { manager.removeUpdates(listener) }; signal?.cancel()
        }

        fun finish(location: Location?) {
            if (settled.compareAndSet(false, true)) {
                release(); continuation.resumeWith(Result.success(location))
            }
        }
        listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                finish(location)
            }

            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onStatusChanged(
                provider: String?, status: Int, extras: android.os.Bundle?
            ) = Unit
        }
        continuation.invokeOnCancellation { settled.set(true); release() }
        try {
            if (continuation.isActive) {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                if (Build.VERSION.SDK_INT >= 30 && continuation.isActive && !settled.get()) manager.getCurrentLocation(
                    provider,
                    signal,
                    { it.run() }) { location ->
                    finish(
                        location
                    )
                }
                if (!continuation.isActive || settled.get()) release()
            }
        } catch (_: Exception) {
            finish(null)
        }
    }
