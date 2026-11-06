package app.zura.ui.theme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import app.zura.data.SettingsStore
import app.zura.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Automatic day / night theme: sunrise and sunset computed ON the phone (the standard solar
 * equation, ±1–2 min). The place is the phone's last known approximate location if the user
 * allowed it — rounded to ~10 km and never sent anywhere — otherwise an estimate from the time zone.
 */
object SunClock {
    data class Place(val lat: Double, val lon: Double, val estimated: Boolean)
    data class Day(val sunrise: Instant?, val sunset: Instant?, val place: Place)

    /** Sunrise / sunset on [date] (null = the sun doesn't rise / set that day: polar). */
    fun times(lat: Double, lon: Double, date: LocalDate): Pair<Instant?, Instant?> {
        val rad = Math.PI / 180
        // Julian date at 00:00 UTC of [date]. (Using noon here made n round up to TOMORROW: the
        // app compared "now" with tomorrow's sunrise / sunset and stayed on the night theme.)
        val jd = date.toEpochDay() + 2440587.5
        val n = Math.ceil(jd - 2451545.0 + 0.0008)
        val jStar = n - lon / 360.0
        val m = (357.5291 + 0.98560028 * jStar) % 360
        val c = 1.9148 * sin(m * rad) + 0.02 * sin(2 * m * rad) + 0.0003 * sin(3 * m * rad)
        val lambda = (m + c + 180 + 102.9372) % 360
        val transit = 2451545.0 + jStar + 0.0053 * sin(m * rad) - 0.0069 * sin(2 * lambda * rad)
        val decl = asin(sin(lambda * rad) * sin(23.4397 * rad))
        val cosW = (sin(-0.833 * rad) - sin(lat * rad) * sin(decl)) / (cos(lat * rad) * cos(decl))
        if (cosW < -1) return null to null // midnight sun: always day
        if (cosW > 1) return null to null  // polar night
        val w = acos(cosW) / rad / 360.0
        fun toInstant(j: Double) = Instant.ofEpochMilli(((j - 2440587.5) * 86_400_000).toLong())
        return toInstant(transit - w) to toInstant(transit + w)
    }

    fun place(c: Context): Place {
        val prefs = c.getSharedPreferences("zura", Context.MODE_PRIVATE)
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val lm = c.getSystemService(LocationManager::class.java)
                lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            }.getOrNull()?.let { loc ->
                // ~10 km precision is plenty for the sun.
                val lat = Math.round(loc.latitude * 10) / 10.0
                val lon = Math.round(loc.longitude * 10) / 10.0
                prefs.edit().putFloat("sun_lat", lat.toFloat()).putFloat("sun_lon", lon.toFloat()).apply()
                return Place(lat, lon, false)
            }
        }
        if (prefs.contains("sun_lat")) return Place(prefs.getFloat("sun_lat", 0f).toDouble(), prefs.getFloat("sun_lon", 0f).toDouble(), false)
        // No location: the time zone's offset gives the longitude (15° per hour); a mid latitude.
        val offsetH = java.util.TimeZone.getDefault().rawOffset / 3_600_000.0
        return Place(30.0, offsetH * 15.0, true)
    }

    fun today(c: Context): Day {
        val p = place(c)
        val (rise, set) = times(p.lat, p.lon, LocalDate.now(ZoneId.systemDefault()))
        return Day(rise, set, p)
    }
}

/** Keeps [SettingsStore.daylight] true between sunrise and sunset while the automatic theme is on. */
class ThemeClock(private val app: Context, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val _today = MutableStateFlow<SunClock.Day?>(null)
    private var lastLogged: Boolean? = null
    /** Today's sunrise / sunset (for the settings screen). */
    val today: StateFlow<SunClock.Day?> = _today.asStateFlow()

    fun start() = scope.launch {
        settings.autoTheme.collectLatest { auto ->
            if (!auto) return@collectLatest
            while (true) {
                val day = SunClock.today(app)
                _today.value = day
                val now = Instant.now()
                val rise = day.sunrise; val set = day.sunset
                val up = if (rise != null && set != null) now in rise..set else {
                    // Polar day / night: decide by the season (sun at noon above the horizon or not).
                    val noon = java.time.ZonedDateTime.now().withHour(12)
                    val decl = 23.44 * sin(Math.toRadians(360.0 / 365 * (noon.dayOfYear - 81)))
                    90 - kotlin.math.abs(day.place.lat - decl) > 0
                }
                if (up != lastLogged) {
                    lastLogged = up
                    val tf = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
                    DiagLog.record("theme", detail = "auto → ${if (up) "day" else "night"} (sunrise ${rise?.let(tf::format)}, sunset ${set?.let(tf::format)}, place ${if (day.place.estimated) "from time zone" else "approx. location"})")
                }
                settings.setDaylight(up)
                // Sleep until the next sunrise / sunset (re-checked at least every 30 min: travel, time-zone change).
                val next = listOfNotNull(rise, set).filter { it.isAfter(now) }.minOrNull()
                val wait = next?.let { it.toEpochMilli() - now.toEpochMilli() + 1_000 } ?: Long.MAX_VALUE
                delay(wait.coerceIn(60_000, 30 * 60_000L))
            }
        }
    }

    /** Location permission just granted: recompute at once. */
    fun refresh() { _today.value = SunClock.today(app); _today.value?.let { d ->
        val now = Instant.now()
        if (d.sunrise != null && d.sunset != null) settings.setDaylight(now in d.sunrise..d.sunset)
    } }
}
