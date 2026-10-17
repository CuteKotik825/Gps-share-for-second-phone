package com.example.gpsbridge

import android.location.Location
import org.json.JSONObject

/** Одна GPS-точка. Передаётся между телефонами как одна JSON-строка. */
data class Fix(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val speed: Float?,
    val bearing: Float?,
    val accuracy: Float?,
    val satellites: Int?
) {

    fun toJson(key: String): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("t", time)
        o.put("lat", lat)
        o.put("lon", lon)
        if (alt != null) o.put("alt", alt)
        if (speed != null) o.put("spd", speed.toDouble())
        if (bearing != null) o.put("brg", bearing.toDouble())
        if (accuracy != null) o.put("acc", accuracy.toDouble())
        if (satellites != null) o.put("sat", satellites)
        if (key.isNotEmpty()) o.put("k", key)
        return o.toString()
    }

    companion object {

        fun fromLocation(l: Location): Fix {
            val sats = l.extras?.getInt("satellites", -1) ?: -1
            return Fix(
                time = System.currentTimeMillis(),
                lat = l.latitude,
                lon = l.longitude,
                alt = if (l.hasAltitude()) l.altitude else null,
                speed = if (l.hasSpeed()) l.speed else null,
                bearing = if (l.hasBearing()) l.bearing else null,
                accuracy = if (l.hasAccuracy()) l.accuracy else null,
                satellites = if (sats >= 0) sats else null
            )
        }

        /** Возвращает null, если строка битая, координаты вне диапазона или ключ не совпал. */
        fun fromJson(s: String, key: String): Fix? {
            return try {
                val o = JSONObject(s)
                if (key.isNotEmpty() && o.optString("k", "") != key) return null
                val lat = o.getDouble("lat")
                val lon = o.getDouble("lon")
                if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
                Fix(
                    time = o.getLong("t"),
                    lat = lat,
                    lon = lon,
                    alt = if (o.has("alt")) o.getDouble("alt") else null,
                    speed = if (o.has("spd")) o.getDouble("spd").toFloat() else null,
                    bearing = if (o.has("brg")) o.getDouble("brg").toFloat() else null,
                    accuracy = if (o.has("acc")) o.getDouble("acc").toFloat() else null,
                    satellites = if (o.has("sat")) o.getInt("sat") else null
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
