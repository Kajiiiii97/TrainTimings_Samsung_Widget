package com.traintimings.vvs

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Thin client for the public VVS EFA endpoints (the same ones the VVS website and app use). */
object VvsApi {
    private const val BASE = "https://www3.vvs.de/mngvvs/"
    private const val COMMON =
        "SpEncId=0&coordOutputFormat=EPSG:4326&outputFormat=rapidJSON&serverInfo=1&version=10.2.10.139"

    fun searchStops(query: String): List<Stop> {
        val url = "${BASE}XML_STOPFINDER_REQUEST?$COMMON&type_sf=any&name_sf=${enc(query)}"
        return VvsParser.parseStops(get(url))
    }

    fun departures(stopId: String, limit: Int): List<Departure> = board(stopId, limit).departures

    /** Departures plus the stop name VVS resolved the id to. */
    fun board(stopId: String, limit: Int): Board {
        val url = "${BASE}XML_DM_REQUEST?$COMMON&type_dm=any&name_dm=${enc(stopId)}" +
            "&mode=direct&useRealtime=1&deleteAssignedStops=1&limit=$limit"
        val body = get(url)
        return Board(VvsParser.parseStopName(body), VvsParser.parseDepartures(body))
    }

    data class Board(val stopName: String?, val departures: List<Departure>)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "TrainTimingsWidget/1.0 (Android)")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("VVS returned HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
