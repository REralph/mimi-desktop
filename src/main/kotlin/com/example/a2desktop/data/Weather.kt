package com.example.a2desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.a2desktop.logic.SeasonalUv
import com.example.a2desktop.logic.UvLookup
import com.example.a2desktop.logic.forecastUv
import com.example.a2desktop.logic.seasonalUv
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// The UV forecast behind the skin-care plan. This is the ONLY thing in the desktop app that talks to the
// internet, and only after the user has typed a town on the Skin care page: the town's name is sent to
// Open-Meteo's search to find it, then its coordinates (rounded to about a kilometre) to get the daily UV
// forecast. No account, no key, nothing else leaves the PC. Without a town, nothing is ever sent and the
// plan uses the seasonal estimate. Weather data by Open-Meteo.com (CC BY 4.0).

/** A town the user chose for the forecast. */
@Serializable
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val region: String? = null,
    val country: String? = null,
) {
    /** "Leeds, England, United Kingdom" — enough for the user to tell two towns with one name apart. */
    val label: String get() = listOfNotNull(name, region?.takeIf { it != name }, country).joinToString(", ")
}

/** The daily maximum UV index and highest temperature (°C) for the last couple of weeks and the next two, as of [fetchedAtMillis]. */
class UvForecast(
    val place: Place,
    val fetchedAtMillis: Long,
    val uvByDay: Map<LocalDate, Double>,
    val tempMaxByDay: Map<LocalDate, Double> = emptyMap(),
)

/** What one weather request gives back: UV and the day's highest temperature in °C, by date. */
data class DailyWeather(val uv: Map<LocalDate, Double>, val tempMax: Map<LocalDate, Double>)

sealed interface WeatherResult<out T> {
    data class Ok<T>(val value: T) : WeatherResult<T>
    data class Failed(val message: String) : WeatherResult<Nothing>
}

/** Where towns and forecasts come from — the real one is [OpenMeteoService]; tests use a fake. */
interface WeatherService {
    fun search(name: String): WeatherResult<List<Place>>
    fun uvForecast(place: Place): WeatherResult<DailyWeather>
}

// ---- Open-Meteo ----

@Serializable
private data class GeoResponse(val results: List<GeoPlace> = emptyList())

@Serializable
private data class GeoPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
)

@Serializable
private data class ForecastResponse(val daily: DailyUv? = null)

@Serializable
private data class DailyUv(
    val time: List<String> = emptyList(),
    @SerialName("uv_index_max") val uv: List<Double?> = emptyList(),
    @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/** The towns in an Open-Meteo search answer (none at all means no `results` key). */
internal fun parseSearch(body: String): List<Place> =
    lenientJson.decodeFromString<GeoResponse>(body).results.map { Place(it.name, it.latitude, it.longitude, it.admin1, it.country) }

/** The daily UV maximum and highest temperature per date in an Open-Meteo forecast answer; days without a value are left out. */
internal fun parseDaily(body: String): DailyWeather {
    val daily = lenientJson.decodeFromString<ForecastResponse>(body).daily ?: return DailyWeather(emptyMap(), emptyMap())
    val days = daily.time.map(LocalDate::parse)
    return DailyWeather(
        uv = days.zip(daily.uv).mapNotNull { (day, uv) -> uv?.let { day to it } }.toMap(),
        tempMax = days.zip(daily.tempMax).mapNotNull { (day, t) -> t?.let { day to it } }.toMap(),
    )
}

/** Open-Meteo (open-meteo.com): free for personal use, no account or key. */
class OpenMeteoService(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
) : WeatherService {
    override fun search(name: String): WeatherResult<List<Place>> = get(
        "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name.trim(), Charsets.UTF_8)}&count=5&language=en&format=json",
        ::parseSearch,
    )

    override fun uvForecast(place: Place): WeatherResult<DailyWeather> = get(
        "https://api.open-meteo.com/v1/forecast?latitude=${coordinate(place.latitude)}&longitude=${coordinate(place.longitude)}" +
            "&daily=uv_index_max,temperature_2m_max&timezone=auto&past_days=14&forecast_days=16",
        ::parseDaily,
    )

    /** Two decimals is about a kilometre — all a UV forecast needs, and no more of the user's location than that. */
    private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    private fun <T> get(url: String, parse: (String) -> T): WeatherResult<T> = try {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) WeatherResult.Ok(parse(response.body()))
        else WeatherResult.Failed("the weather service answered with an error (${response.statusCode()})")
    } catch (e: java.io.IOException) {
        WeatherResult.Failed("couldn't reach the weather service — check the internet connection")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        WeatherResult.Failed("the request was interrupted")
    } catch (e: Exception) {
        WeatherResult.Failed("the weather service's answer wasn't understood")
    }
}

// ---- The store ----

@Serializable
internal data class WeatherFile(
    val place: Place? = null,
    val fetchedAtMillis: Long = 0,
    /** Epoch day -> UV index. */
    val uv: Map<Long, Double> = emptyMap(),
    /** Epoch day -> the day's highest temperature in °C (absent in files saved before temperatures were shown). */
    val tempMax: Map<Long, Double> = emptyMap(),
)

/** A forecast older than this isn't trusted any more: the plan goes back to the seasonal estimate. */
private const val TRUST_HOURS = 48L

/** How long a fetched forecast counts as fresh enough not to fetch again. */
private const val FRESH_HOURS = 3L

/**
 * The user's town and the last UV forecast for it, kept in `weather.json` on this PC. With no town (or no
 * [service]) it never touches the network. A failed refresh keeps the last forecast and says why; a missing or
 * damaged file just means "no town set". [file] null keeps everything in memory (previews and tests).
 */
class WeatherStore(
    private val file: File? = null,
    private val service: WeatherService? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    var place by mutableStateOf<Place?>(null)
        private set
    var forecast by mutableStateOf<UvForecast?>(null)
        private set

    /** Towns found by the last search, for the user to pick from. */
    var matches by mutableStateOf<List<Place>>(emptyList())
        private set

    /** What went wrong, in plain words, or null. */
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** Whether this store can reach the internet at all (false in previews and tests without a service). */
    val online: Boolean get() = service != null

    init {
        val saved = file?.let { f -> runCatching { json.decodeFromString<WeatherFile>(f.readText()) }.getOrNull() }
        if (saved?.place != null) {
            place = saved.place
            if (saved.uv.isNotEmpty()) {
                forecast = UvForecast(
                    saved.place,
                    saved.fetchedAtMillis,
                    saved.uv.mapKeys { LocalDate.ofEpochDay(it.key) },
                    saved.tempMax.mapKeys { LocalDate.ofEpochDay(it.key) },
                )
            }
        }
    }

    /** Looks for a town by name; the answer lands in [matches]. */
    fun search(query: String) {
        val svc = service
        if (query.isBlank()) {
            message = "Type a town first."
            return
        }
        if (svc == null) {
            message = "The weather forecast isn't available here."
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                when (val result = svc.search(query)) {
                    is WeatherResult.Ok -> {
                        matches = result.value
                        message = if (result.value.isEmpty()) "No town found called \"${query.trim()}\"." else null
                    }
                    is WeatherResult.Failed -> message = "Couldn't search: ${result.message}."
                }
            } finally {
                busy = false
            }
        }
    }

    /** Uses [chosen] for the forecast from now on, and fetches it. */
    fun choose(chosen: Place) {
        place = chosen
        forecast = null
        matches = emptyList()
        message = null
        save()
        refresh(force = true)
    }

    /** Stops using a forecast: forgets the town and the forecast, and nothing more is sent anywhere. */
    fun clear() {
        place = null
        forecast = null
        matches = emptyList()
        message = null
        runCatching { file?.delete() }
    }

    /** Fetches the forecast for the chosen town unless a recent one is held (or [force]). Never throws. */
    fun refresh(force: Boolean = false) {
        val svc = service ?: return
        val target = place ?: return
        val held = forecast
        if (!force && held != null && held.place == target && clock() - held.fetchedAtMillis < Duration.ofHours(FRESH_HOURS).toMillis()) return
        if (busy) return
        busy = true
        scope.launch {
            try {
                when (val result = svc.uvForecast(target)) {
                    is WeatherResult.Ok -> {
                        forecast = UvForecast(target, clock(), result.value.uv, result.value.tempMax)
                        message = null
                        save()
                    }
                    is WeatherResult.Failed -> message =
                        "Couldn't update the forecast: ${result.message}." + if (forecast != null) " Using the last one." else " Using the seasonal estimate."
                }
            } finally {
                busy = false
            }
        }
    }

    /** The sun for the skin-care plan: the forecast while it is recent enough, the seasonal estimate otherwise. */
    fun uvLookup(): UvLookup {
        val held = forecast ?: return SeasonalUv
        if (clock() - held.fetchedAtMillis > Duration.ofHours(TRUST_HOURS).toMillis()) return SeasonalUv
        return UvLookup { date -> held.uvByDay[date]?.let { forecastUv(it, held.tempMaxByDay[date]) } ?: seasonalUv(date) }
    }

    private fun save() {
        val f = file ?: return
        val chosen = place ?: return
        val held = forecast
        runCatching {
            f.parentFile?.mkdirs()
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(
                json.encodeToString(
                    WeatherFile(
                        chosen,
                        held?.fetchedAtMillis ?: 0,
                        held?.uvByDay?.mapKeys { it.key.toEpochDay() } ?: emptyMap(),
                        held?.tempMaxByDay?.mapKeys { it.key.toEpochDay() } ?: emptyMap(),
                    ),
                ),
            )
            Files.move(temp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** The real thing: the file next to the pairing in `%APPDATA%\a2-desktop`, and Open-Meteo for the forecast. */
        fun default(): WeatherStore = WeatherStore(
            File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "a2-desktop"), "weather.json"),
            OpenMeteoService(),
        )
    }
}
