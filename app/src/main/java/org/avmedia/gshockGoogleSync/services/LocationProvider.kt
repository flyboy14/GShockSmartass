package org.avmedia.gshockGoogleSync.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.text.uppercase
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationProvider {
    private const val PREF_NAME = "location_provider_cache"
    private const val KEY_LATITUDE = "cached_latitude"
    private const val KEY_LONGITUDE = "cached_longitude"
    private const val KEY_HAS_CACHED_LOCATION = "has_cached_location"

    sealed interface LocationResult {
        data class Success(val location: Location) : LocationResult
        data object NoProvider : LocationResult
        data object NoLocation : LocationResult
    }

    data class Location(
        val latitude: Double,
        val longitude: Double
    ) {
        init {
            require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90 degrees" }
            require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180 degrees" }
        }

        companion object {
            fun fromAndroidLocation(location: android.location.Location): Location =
                Location(location.latitude, location.longitude)
        }
    }

    /**
     * Freshest location the phone has, else the last one this app saw. A refused or failing read
     * (e.g. Battery Saver allowing location only to apps on screen) also falls back to the saved
     * one — before, an exception skipped the fallback and returned null.
     */
    @SuppressLint("MissingPermission")
    fun getLocation(context: Context): Location? {
        val result = runCatching { getLocationResult(context) }.getOrNull()
        // Without Google Play Services (GrapheneOS) nobody fills the providers' last fix: ask for one.
        if (result !is LocationResult.Success) requestFreshAsync(context)
        return when (result) {
            is LocationResult.Success -> {
                cacheLocation(context, result.location)
                result.location
            }
            else -> runCatching { getCachedLocation(context) }.getOrNull()
        }
    }

    private const val FRESH_MAX_AGE_MS = 60 * 60_000L
    private const val REQUEST_GAP_MS = 5 * 60_000L
    private val lastRequestAt = AtomicLong(0)

    /**
     * Asks the providers for a current fix when the newest last-known one is missing or older than
     * an hour; caches it. On stock Android Google Play Services keeps the last fix warm for every
     * app; on GrapheneOS (no Play) the last fix stays null until some app requests one.
     */
    suspend fun refreshIfStale(context: Context, timeoutMs: Long = 30_000): Location? {
        val newest = runCatching { newestLastKnown(context) }.getOrNull()
        if (newest != null && System.currentTimeMillis() - newest.time < FRESH_MAX_AGE_MS) {
            return Location.fromAndroidLocation(newest).also { cacheLocation(context, it) }
        }
        return withTimeoutOrNull(timeoutMs) { requestCurrent(context) }?.also { cacheLocation(context, it) }
    }

    private fun requestFreshAsync(context: Context) {
        val now = System.currentTimeMillis()
        val last = lastRequestAt.get()
        if (now - last < REQUEST_GAP_MS || !lastRequestAt.compareAndSet(last, now)) return
        val app = context.applicationContext
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        usableProviders(lm).forEach { p ->
            runCatching {
                LocationManagerCompat.getCurrentLocation(lm, p, null as CancellationSignal?, ContextCompat.getMainExecutor(app)) { loc ->
                    if (loc != null) runCatching { cacheLocation(app, Location.fromAndroidLocation(loc)) }
                }
            }
        }
    }

    /** First fix any usable provider delivers (network answers in seconds, GPS may take long). */
    @SuppressLint("MissingPermission")
    private suspend fun requestCurrent(context: Context): Location? {
        val app = context.applicationContext
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = usableProviders(lm)
        if (providers.isEmpty()) return null
        lastRequestAt.set(System.currentTimeMillis())
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            val pending = java.util.concurrent.atomic.AtomicInteger(providers.size)
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            cont.invokeOnCancellation { signal.cancel() }
            providers.forEach { p ->
                val started = runCatching {
                    LocationManagerCompat.getCurrentLocation(lm, p, signal, ContextCompat.getMainExecutor(app)) { loc ->
                        val left = pending.decrementAndGet()
                        if (loc != null) { if (done.compareAndSet(false, true)) { signal.cancel(); cont.resume(Location.fromAndroidLocation(loc)) } }
                        else if (left == 0 && done.compareAndSet(false, true)) cont.resume(null)
                    }
                }.isSuccess
                if (!started && pending.decrementAndGet() == 0 && done.compareAndSet(false, true)) cont.resume(null)
            }
        }
    }

    /** Enabled providers this app may use: GPS needs precise location, network/fused work with coarse. */
    private fun usableProviders(lm: LocationManager): List<String> {
        return buildList {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
    }

    @SuppressLint("MissingPermission")
    private fun newestLastKnown(context: Context): android.location.Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .plus(if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList())
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun getLocationResult(context: Context): LocationResult {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationResult.NoProvider

        // The most recent fix any provider has (network alone is often empty or old).
        val providers = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return providers
            .mapNotNull { p -> runCatching { locationManager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { LocationResult.Success(Location.fromAndroidLocation(it)) }
            ?: LocationResult.NoLocation
    }

    private fun cacheLocation(context: Context, location: Location) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(KEY_LATITUDE, location.latitude.toFloat())
            putFloat(KEY_LONGITUDE, location.longitude.toFloat())
            putBoolean(KEY_HAS_CACHED_LOCATION, true)
            apply()
        }
    }

    private fun getCachedLocation(context: Context): Location? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val hasCachedLocation = prefs.getBoolean(KEY_HAS_CACHED_LOCATION, false)
        if (!hasCachedLocation) {
            return null
        }

        val latitude = prefs.getFloat(KEY_LATITUDE, 0f).toDouble()
        val longitude = prefs.getFloat(KEY_LONGITUDE, 0f).toDouble()

        return runCatching {
            Location(latitude, longitude)
        }.getOrNull()
    }

    /**
     * Provides a reliable, offline-first country code using a hybrid strategy.
     *
     * 1. Tries to get the country from the SIM card's mobile network (fast, offline).
     * 2. If that fails, it tries to use the Geocoder with the physical location (may use network).
     * 3. As a last resort, it falls back to the user's device language/region setting.
     *
     * Note: This function is `suspend` because Geocoder`s modern API is asynchronous.
     *
     * @param context The application context.
     * @return A two-letter ISO country code (e.g., "US", "DE", "IN") or null if undetermined.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCountryCode(context: Context): String? {
        var countryCode: String?

        // --- STRATEGY 1: TRY OFFLINE NETWORK DETECTION FIRST (FAST & RELIABLE) ---
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        countryCode = telephonyManager?.networkCountryIso?.uppercase(java.util.Locale.US)
        if (!countryCode.isNullOrBlank()) {
            return countryCode
        }

        // --- STRATEGY 2: IF THAT FAILS, TRY GEOCODER (MAY USE NETWORK) ---
        // Requires location permission, which the calling code should have.
        getLocation(context)?.let { location ->
            try {
                val geocoder = Geocoder(context, java.util.Locale.getDefault())
                val addresses = geocodeFromLocation(geocoder, location.latitude, location.longitude, 1)
                countryCode = addresses?.firstOrNull()?.countryCode?.uppercase(java.util.Locale.US)
                if (!countryCode.isNullOrBlank()) {
                    return countryCode
                }
            } catch (e: Exception) {
                // Geocoder failed, proceed to the next strategy.
            }
        }

        // --- STRATEGY 3: AS A LAST RESORT, FALL BACK TO PHONE'S LOCALE SETTING ---
        countryCode = java.util.Locale.getDefault().country.uppercase(java.util.Locale.US)
        if (!countryCode.isBlank()) {
            return countryCode
        }

        return null // Return null if all strategies fail.
    }

    @SuppressLint("NewApi")
    private suspend fun geocodeFromLocation(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double,
        maxResults: Int = 1
    ): List<android.location.Address>? = suspendCancellableCoroutine { cont ->
        val listener = Geocoder.GeocodeListener { addresses ->
            if (cont.isActive) cont.resume(addresses)
        }

        try {
            geocoder.getFromLocation(latitude, longitude, maxResults, listener)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(emptyList())
        }
    }
}
