package com.example.a2desktop

import com.example.a2desktop.data.DailyWeather
import com.example.a2desktop.data.Place
import com.example.a2desktop.data.WeatherResult
import com.example.a2desktop.data.WeatherService
import com.example.a2desktop.data.WeatherStore
import com.example.a2desktop.logic.UvBasis
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** The UV forecast: what the weather service's answers are turned into, and how the store keeps them. */
class WeatherTest {
    private val dir: File = Files.createTempDirectory("a2-weather").toFile()
    private val file = File(dir, "weather.json")
    private val oct20 = LocalDate.of(2026, 10, 20)
    private val leeds = Place("Leeds", 53.79648, -1.54785, "England", "United Kingdom")
    private var now = 1_000_000_000L

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private class FakeWeather : WeatherService {
        var searchAnswer: WeatherResult<List<Place>> = WeatherResult.Ok(emptyList())
        var uvAnswer: WeatherResult<DailyWeather> = WeatherResult.Ok(DailyWeather(emptyMap(), emptyMap()))
        var searches = mutableListOf<String>()
        var forecastsFor = mutableListOf<Place>()
        override fun search(name: String): WeatherResult<List<Place>> { searches += name; return searchAnswer }
        override fun uvForecast(place: Place): WeatherResult<DailyWeather> { forecastsFor += place; return uvAnswer }
    }

    private fun store(service: WeatherService? = FakeWeather(), f: File? = file) =
        WeatherStore(f, service, CoroutineScope(Dispatchers.Unconfined)) { now }

    // ---- the service's answers ----

    @Test fun aSearchAnswerBecomesTownsWithTheirRegionAndCountry() {
        val body = """{"results":[{"id":1,"name":"Leeds","latitude":53.79648,"longitude":-1.54785,"elevation":47.0,"country":"United Kingdom","admin1":"England","timezone":"Europe/London"},
            {"id":2,"name":"Leeds","latitude":33.5482,"longitude":-86.5444,"country":"United States","admin1":"Alabama"}],"generationtime_ms":0.5}"""
        val places = com.example.a2desktop.data.parseSearch(body)
        assertEquals(2, places.size)
        assertEquals("Leeds, England, United Kingdom", places[0].label)
        assertEquals("Leeds, Alabama, United States", places[1].label)
        assertEquals(53.79648, places[0].latitude)
    }

    @Test fun aSearchWithNoMatchHasNoResultsKey() {
        assertEquals(emptyList(), com.example.a2desktop.data.parseSearch("""{"generationtime_ms":0.2}"""))
    }

    @Test fun aForecastAnswerBecomesTheUvAndTheHighestTemperatureForEachDateAndSkipsDaysWithNoValue() {
        val body = """{"latitude":53.8,"longitude":-1.55,"timezone":"Europe/London","daily_units":{"time":"iso8601","uv_index_max":"","temperature_2m_max":"°C"},
            "daily":{"time":["2026-10-20","2026-10-21","2026-10-22"],"uv_index_max":[0.65,null,2.25],"temperature_2m_max":[13.1,12.2,null]}}"""
        val weather = com.example.a2desktop.data.parseDaily(body)
        assertEquals(mapOf(oct20 to 0.65, oct20.plusDays(2) to 2.25), weather.uv)
        assertEquals(mapOf(oct20 to 13.1, oct20.plusDays(1) to 12.2), weather.tempMax)
    }

    @Test fun aForecastAnswerWithNothingInItGivesNoDays() {
        val weather = com.example.a2desktop.data.parseDaily("""{"latitude":1.0}""")
        assertEquals(emptyMap(), weather.uv)
        assertEquals(emptyMap(), weather.tempMax)
    }

    // ---- choosing a town ----

    @Test fun nothingIsEverRequestedUntilATownHasBeenChosen() {
        val service = FakeWeather()
        val weather = store(service)
        weather.refresh()
        weather.refresh(force = true)
        assertTrue(service.searches.isEmpty() && service.forecastsFor.isEmpty(), "no town: the forecast is never asked for")
        assertNull(weather.place)
        assertEquals(UvBasis.SEASON, weather.uvLookup().on(oct20).basis)
    }

    @Test fun searchingListsTheMatchesAndChoosingOneFetchesItsForecast() {
        val service = FakeWeather()
        service.searchAnswer = WeatherResult.Ok(listOf(leeds))
        service.uvAnswer = WeatherResult.Ok(DailyWeather(mapOf(oct20 to 4.0), emptyMap()))
        val weather = store(service)
        weather.search("  Leeds ")
        assertEquals(listOf("  Leeds "), service.searches)
        assertEquals(listOf(leeds), weather.matches)
        assertTrue(service.forecastsFor.isEmpty(), "a search alone doesn't fetch a forecast")
        weather.choose(leeds)
        assertEquals(leeds, weather.place)
        assertTrue(weather.matches.isEmpty())
        assertEquals(listOf(leeds), service.forecastsFor)
        val reading = weather.uvLookup().on(oct20)
        assertEquals(UvBasis.FORECAST, reading.basis)
        assertEquals(4.0, reading.index)
        assertEquals(UvBasis.SEASON, weather.uvLookup().on(oct20.plusDays(30)).basis, "a day the forecast doesn't cover")
    }

    @Test fun theTownAndForecastAreRememberedAcrossARestart() {
        val service = FakeWeather().apply { uvAnswer = WeatherResult.Ok(DailyWeather(mapOf(oct20 to 3.0, oct20.plusDays(1) to 1.0), emptyMap())) }
        store(service).choose(leeds)
        val reopened = store(FakeWeather())
        assertEquals(leeds, reopened.place)
        assertNotNull(reopened.forecast)
        assertEquals(3.0, reopened.uvLookup().on(oct20).index)
    }

    @Test fun theForecastKeepsEachDaysTemperatureAndHandsItToThePlan() {
        val service = FakeWeather().apply {
            uvAnswer = WeatherResult.Ok(DailyWeather(mapOf(oct20 to 2.0, oct20.minusDays(2) to 1.0), mapOf(oct20 to 14.4, oct20.minusDays(2) to 11.0)))
        }
        val weather = store(service)
        weather.choose(leeds)
        assertEquals(14.4, weather.uvLookup().on(oct20).tempMaxC)
        assertEquals(11.0, weather.uvLookup().on(oct20.minusDays(2)).tempMaxC, "the recent past is kept too")
        assertNull(weather.uvLookup().on(oct20.plusDays(40)).tempMaxC, "no temperature beyond the forecast")
        val reopened = store(FakeWeather())
        assertEquals(14.4, reopened.uvLookup().on(oct20).tempMaxC, "saved with the forecast")
    }

    @Test fun aForecastFileSavedBeforeTemperaturesExistedStillOpens() {
        file.writeText("""{"place":{"name":"Leeds","latitude":53.8,"longitude":-1.55},"fetchedAtMillis":$now,"uv":{"${oct20.toEpochDay()}":3.0}}""")
        val weather = store(FakeWeather())
        assertEquals(3.0, weather.uvLookup().on(oct20).index)
        assertNull(weather.uvLookup().on(oct20).tempMaxC)
    }

    @Test fun aFreshForecastIsNotFetchedAgainButAnOldOneIsOrAForcedRefresh() {
        val service = FakeWeather().apply { uvAnswer = WeatherResult.Ok(DailyWeather(mapOf(oct20 to 2.0), emptyMap())) }
        val weather = store(service)
        weather.choose(leeds)
        assertEquals(1, service.forecastsFor.size)
        now += 60 * 60 * 1000L // an hour later
        weather.refresh()
        assertEquals(1, service.forecastsFor.size, "still fresh")
        now += 3 * 60 * 60 * 1000L // four hours after the fetch
        weather.refresh()
        assertEquals(2, service.forecastsFor.size, "older than three hours")
        weather.refresh(force = true)
        assertEquals(3, service.forecastsFor.size)
    }

    // ---- when things go wrong ----

    @Test fun aFailedRefreshKeepsTheLastForecastAndSaysSo() {
        val service = FakeWeather().apply { uvAnswer = WeatherResult.Ok(DailyWeather(mapOf(oct20 to 4.0), emptyMap())) }
        val weather = store(service)
        weather.choose(leeds)
        service.uvAnswer = WeatherResult.Failed("couldn't reach the weather service — check the internet connection")
        weather.refresh(force = true)
        assertTrue(weather.message!!.contains("Using the last one"), weather.message)
        assertEquals(4.0, weather.uvLookup().on(oct20).index, "the old forecast is still used")
        assertFalse(weather.busy)
    }

    @Test fun aFailedFirstFetchFallsBackToTheSeasonalEstimateAndSaysSo() {
        val service = FakeWeather().apply { uvAnswer = WeatherResult.Failed("the weather service answered with an error (500)") }
        val weather = store(service)
        weather.choose(leeds)
        assertTrue(weather.message!!.contains("seasonal estimate"), weather.message)
        assertEquals(UvBasis.SEASON, weather.uvLookup().on(oct20).basis)
        assertEquals(leeds, weather.place, "the town is still remembered, to try again later")
    }

    @Test fun aForecastOlderThanTwoDaysIsNoLongerTrusted() {
        val service = FakeWeather().apply { uvAnswer = WeatherResult.Ok(DailyWeather(mapOf(oct20 to 4.0), emptyMap())) }
        val weather = store(service)
        weather.choose(leeds)
        assertEquals(UvBasis.FORECAST, weather.uvLookup().on(oct20).basis)
        now += 49 * 60 * 60 * 1000L
        assertEquals(UvBasis.SEASON, weather.uvLookup().on(oct20).basis)
    }

    @Test fun aSearchThatFindsNothingOrFailsExplainsItself() {
        val service = FakeWeather()
        val weather = store(service)
        weather.search("Nowhereville")
        assertTrue(weather.message!!.contains("No town found"), weather.message)
        service.searchAnswer = WeatherResult.Failed("couldn't reach the weather service — check the internet connection")
        weather.search("Leeds")
        assertTrue(weather.message!!.contains("Couldn't search"), weather.message)
        weather.search("   ")
        assertEquals("Type a town first.", weather.message)
        assertEquals(2, service.searches.size, "a blank search is never sent")
    }

    @Test fun withoutAnInternetServiceNothingIsSentAndNothingBreaks() {
        val weather = store(service = null)
        assertFalse(weather.online)
        weather.search("Leeds")
        assertNotNull(weather.message)
        weather.choose(leeds) // remembered, but there is nothing to fetch with
        weather.refresh(force = true)
        assertEquals(leeds, weather.place)
        assertNull(weather.forecast)
    }

    @Test fun stoppingForgetsTheTownTheForecastAndTheFile() {
        val service = FakeWeather().apply { uvAnswer = WeatherResult.Ok(DailyWeather(mapOf(oct20 to 4.0), emptyMap())) }
        val weather = store(service)
        weather.choose(leeds)
        assertTrue(file.exists())
        weather.clear()
        assertNull(weather.place)
        assertNull(weather.forecast)
        assertFalse(file.exists())
        service.forecastsFor.clear()
        weather.refresh(force = true)
        assertTrue(service.forecastsFor.isEmpty(), "after stopping, nothing is requested")
    }

    @Test fun aDamagedFileJustMeansNoTownYet() {
        file.writeText("not json at all")
        val weather = store(FakeWeather())
        assertNull(weather.place)
        assertEquals(UvBasis.SEASON, weather.uvLookup().on(oct20).basis)
    }
}
