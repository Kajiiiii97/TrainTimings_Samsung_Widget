package com.traintimings.vvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

class VvsParserTest {

    private val departuresJson = """
    {
      "locations": [{ "id": "de:08111:2260", "name": "Stuttgart, Pragsattel", "type": "stop" }],
      "stopEvents": [
        {
          "location": { "properties": { "platform": "2" } },
          "departureTimePlanned": "2026-10-08T12:05:00Z",
          "departureTimeEstimated": "2026-10-08T12:07:00Z",
          "transportation": {
            "name": "Stadtbahn U14", "disassembledName": "U14", "number": "U14",
            "product": { "class": 3, "name": "Stadtbahn" },
            "destination": { "name": "Vaihingen" }
          }
        },
        {
          "departureTimePlanned": "2026-10-08T12:02:00Z",
          "transportation": {
            "number": "U12", "disassembledName": null,
            "product": { "class": 3 },
            "destination": { "name": "Dürrlewang" }
          }
        },
        {
          "departureTimePlanned": "2026-10-08T12:10:00Z",
          "realtimeStatus": ["TRIP_CANCELLED"],
          "transportation": { "number": "56", "product": { "class": 5 }, "destination": { "name": "Rosensteinbrücke" } }
        },
        { "departureTimePlanned": "not a time", "transportation": { "number": "S1" } }
      ]
    }
    """

    @Test
    fun parsesAndSortsDepartures() {
        val deps = VvsParser.parseDepartures(departuresJson)
        assertEquals(listOf("U12", "U14", "56"), deps.map { it.line })

        val u12 = deps[0]
        assertEquals("Dürrlewang", u12.destination)
        assertNull(u12.estimated)
        assertEquals(0, u12.delayMinutes)
        assertEquals("", u12.platform)

        val u14 = deps[1]
        assertEquals(2, u14.delayMinutes)
        assertEquals("2", u14.platform)
        assertEquals(Instant.parse("2026-10-08T12:07:00Z"), u14.time)

        assertTrue(deps[2].cancelled)
        assertFalse(u14.cancelled)
    }

    @Test
    fun parsesStopNameFromDepartureBoard() {
        assertEquals("Stuttgart, Pragsattel", VvsParser.parseStopName(departuresJson))
    }

    @Test
    fun parsesOnlyStopsFromStopFinder() {
        val json = """
        { "locations": [
          { "id": "streetID:1", "name": "Hauptstätter Str.", "type": "street", "matchQuality": 900 },
          { "id": "de:08111:6056", "name": "Stuttgart, Hauptbf (A.-Klett-Pl.)", "type": "stop", "matchQuality": 950 },
          { "id": "de:08111:6118", "name": "Stuttgart, Hauptbahnhof (tief)", "type": "stop", "matchQuality": 990 }
        ] }
        """
        val stops = VvsParser.parseStops(json)
        assertEquals(listOf("de:08111:6118", "de:08111:6056"), stops.map { it.id })
    }

    @Test
    fun configFiltersBySelectedLineAndDirection() {
        val deps = VvsParser.parseDepartures(departuresJson)
        val config = WidgetConfig("x", "Pragsattel", listOf(LineDirection("U14", "Vaihingen")))
        assertEquals(listOf("U14"), deps.filter(config::matches).map { it.line })

        val all = WidgetConfig("x", "Pragsattel", emptyList())
        assertEquals(3, deps.count(all::matches))
    }

    @Test
    fun configAndCacheRoundTripThroughJson() {
        val config = WidgetConfig("5002260", "Pragsattel", listOf(LineDirection("U12", "Dürrlewang")))
        assertEquals(config, WidgetConfig.fromJson(JSONObject(config.toJson().toString())))

        val cache = DepartureCache(Instant.ofEpochMilli(1_000), VvsParser.parseDepartures(departuresJson), null)
        assertEquals(cache, DepartureCache.fromJson(JSONObject(cache.toJson().toString())))
    }
}
