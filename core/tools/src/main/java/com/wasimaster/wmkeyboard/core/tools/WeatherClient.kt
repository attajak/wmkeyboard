package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int

/** Snapshot of current conditions from Open-Meteo. Temperatures in °C. */
data class WeatherInfo(
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val windKmh: Double,
    /** Direction the wind comes from, degrees clockwise from north. */
    val windDirectionDeg: Int,
    val pressureHpa: Double,
    val cloudCoverPercent: Int,
    val precipitationMm: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val highC: Double,
    val lowC: Double,
    /** Today's max UV index; negative when the API omits it. */
    val uvIndexMax: Double,
    /** Chance of precipitation today; negative when the API omits it. */
    val precipProbabilityPercent: Int,
    /** Local "HH:mm", empty when the API omits them. */
    val sunrise: String,
    val sunset: String,
    val fetchedAtMillis: Long,
    /** Tomorrow's forecast, null when the response carried only one day. */
    val tomorrowHighC: Double? = null,
    val tomorrowLowC: Double? = null,
    val tomorrowCode: Int? = null,
    val tomorrowPrecipProbabilityPercent: Int? = null,
)

/** One geocoding hit: a place the user can pick as the weather location. */
data class GeoPlace(
    val name: String,
    /** Region + country for disambiguation, e.g. "Dhaka Division, Bangladesh". */
    val region: String,
    val latitude: Float,
    val longitude: Float,
)

/**
 * Minimal Open-Meteo client (no API key, no account). Only called from the
 * weather tool: forecasts when the panel opens, geocoding when the user
 * searches for a place in settings. The keyboard makes no other network
 * requests.
 */
object WeatherClient {

    /** Blocking fetch; call on an IO dispatcher. Throws on any failure. */
    fun fetch(latitude: Float, longitude: Float): WeatherInfo {
        // The base goes on outside the format string: a configured address may
        // carry a '%' of its own.
        val url = ServiceEndpoints.base(ServiceEndpoint.OPEN_METEO) + String.format(
            Locale.US,
            "/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                "is_day,weather_code,wind_speed_10m,wind_direction_10m," +
                "surface_pressure,cloud_cover,precipitation" +
                "&daily=temperature_2m_max,temperature_2m_min,uv_index_max," +
                "precipitation_probability_max,sunrise,sunset,weather_code" +
                // Two days: the second feeds the "weather tomorrow" smart chip.
                "&timezone=auto&forecast_days=2",
            latitude,
            longitude,
        )
        return parse(get(url), System.currentTimeMillis())
    }

    /** Blocking place-name search; call on an IO dispatcher. Throws on failure. */
    fun geocode(query: String): List<GeoPlace> {
        if (query.isBlank()) return emptyList()
        val url = ServiceEndpoints.base(ServiceEndpoint.OPEN_METEO_GEOCODING) + "/v1/search?name=" +
            URLEncoder.encode(query.trim(), "UTF-8") + "&count=5&language=en&format=json"
        val root = Json.parseToJsonElement(get(url)).jsonObject
        val results = root["results"]?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
        return results.map { it.jsonObject }.map { place ->
            GeoPlace(
                name = place.getValue("name").jsonPrimitive.content,
                region = listOfNotNull(
                    place.optString("admin1"),
                    place.optString("country"),
                ).joinToString(", "),
                latitude = place.getValue("latitude").jsonPrimitive.double.toFloat(),
                longitude = place.getValue("longitude").jsonPrimitive.double.toFloat(),
            )
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.WEATHER, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            netCall.status = connection.responseCode
            return netCall.countIn(connection.inputStream).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    internal fun parse(body: String, now: Long): WeatherInfo {
        val root = Json.parseToJsonElement(body).jsonObject
        val current = root.getValue("current").jsonObject
        val daily = root.getValue("daily").jsonObject
        return WeatherInfo(
            temperatureC = current.getValue("temperature_2m").jsonPrimitive.double,
            feelsLikeC = current.getValue("apparent_temperature").jsonPrimitive.double,
            humidityPercent = current.getValue("relative_humidity_2m").jsonPrimitive.int,
            windKmh = current.getValue("wind_speed_10m").jsonPrimitive.double,
            windDirectionDeg = current.optFirstDouble("wind_direction_10m")?.toInt() ?: 0,
            pressureHpa = current.optFirstDouble("surface_pressure") ?: -1.0,
            cloudCoverPercent = current.optFirstDouble("cloud_cover")?.toInt() ?: -1,
            precipitationMm = current.optFirstDouble("precipitation") ?: 0.0,
            weatherCode = current.getValue("weather_code").jsonPrimitive.int,
            isDay = current.getValue("is_day").jsonPrimitive.int == 1,
            highC = daily.firstDouble("temperature_2m_max"),
            lowC = daily.firstDouble("temperature_2m_min"),
            uvIndexMax = daily.optFirstOfArray("uv_index_max")?.jsonPrimitive?.doubleOrNull ?: -1.0,
            precipProbabilityPercent = daily.optFirstOfArray("precipitation_probability_max")
                ?.jsonPrimitive?.doubleOrNull?.toInt() ?: -1,
            sunrise = daily.optFirstOfArray("sunrise")?.jsonPrimitive?.content
                ?.substringAfter('T', "").orEmpty(),
            sunset = daily.optFirstOfArray("sunset")?.jsonPrimitive?.content
                ?.substringAfter('T', "").orEmpty(),
            fetchedAtMillis = now,
            tomorrowHighC = daily.optAt("temperature_2m_max", 1),
            tomorrowLowC = daily.optAt("temperature_2m_min", 1),
            tomorrowCode = daily.optAt("weather_code", 1)?.toInt(),
            tomorrowPrecipProbabilityPercent = daily.optAt("precipitation_probability_max", 1)?.toInt(),
        )
    }

    /** The [index]th element of a daily array, absent or null tolerated. */
    private fun JsonObject.optAt(key: String, index: Int): Double? =
        this[key]?.takeIf { it !is JsonNull }?.jsonArray?.getOrNull(index)
            ?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.optString(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    /** A scalar field in `current` that older responses may lack. */
    private fun JsonObject.optFirstDouble(key: String): Double? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.firstDouble(key: String): Double =
        getValue(key).jsonArray[0].jsonPrimitive.double

    private fun JsonObject.optFirstOfArray(key: String) =
        this[key]?.takeIf { it !is JsonNull }?.jsonArray?.firstOrNull()
            ?.takeIf { it !is JsonNull }

    /**
     * WMO weather-code groups to a short label. The UI resolves the id, so
     * the line follows the language the user reads the keyboard in.
     */
    @StringRes
    fun describeRes(code: Int): Int = when (code) {
        0 -> R.string.core_tools_weather_condition_clear_sky
        1 -> R.string.core_tools_weather_condition_mainly_clear
        2 -> R.string.core_tools_weather_condition_partly_cloudy
        3 -> R.string.core_tools_weather_condition_overcast
        45, 48 -> R.string.core_tools_weather_condition_fog
        51, 53, 55 -> R.string.core_tools_weather_condition_drizzle
        56, 57 -> R.string.core_tools_weather_condition_freezing_drizzle
        61, 63, 65 -> R.string.core_tools_weather_condition_rain
        66, 67 -> R.string.core_tools_weather_condition_freezing_rain
        71, 73, 75 -> R.string.core_tools_weather_condition_snow
        77 -> R.string.core_tools_weather_condition_snow_grains
        80, 81, 82 -> R.string.core_tools_weather_condition_rain_showers
        85, 86 -> R.string.core_tools_weather_condition_snow_showers
        95 -> R.string.core_tools_weather_condition_thunderstorm
        96, 99 -> R.string.core_tools_weather_condition_thunderstorm_hail
        else -> R.string.core_tools_weather_condition_unknown
    }

    fun emoji(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    fun toDisplay(celsius: Double, fahrenheit: Boolean): Int =
        Math.round(if (fahrenheit) celsius * 9 / 5 + 32 else celsius).toInt()

    /** "NW" style arrow-friendly compass point for a wind direction. */
    fun windCardinal(degrees: Int): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((degrees % 360) + 360) % 360 + 22) / 45 % 8]
    }

    /**
     * Wind speed and direction in the same system as the temperature.
     *
     * The API answers in km/h and the panel printed that whatever the
     * temperature unit was, so a US user got 72°F beside 11 km/h — half
     * imperial and half metric, which is nobody's convention. The unit symbols
     * and the compass letters stay as they are: they are international.
     */
    fun formatWind(windKmh: Double, directionDeg: Int, fahrenheit: Boolean): String {
        val speed = if (fahrenheit) windKmh * KM_PER_HOUR_IN_MPH else windKmh
        val unit = if (fahrenheit) "mph" else "km/h"
        return "${Math.round(speed)} $unit ${windCardinal(directionDeg)}"
    }

    /** One km/h in mph. */
    private const val KM_PER_HOUR_IN_MPH = 0.621371
}
