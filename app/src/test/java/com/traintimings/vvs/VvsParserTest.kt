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
            "id": "vvs:10014: :H:j26", "name": "Stadtbahn U14", "disassembledName": "U14", "number": "U14",
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
        assertEquals("H", u14.direction)
        assertEquals("", u12.direction)
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
    fun stopSelectionFiltersByLineAndDirection() {
        val deps = VvsParser.parseDepartures(departuresJson)
        val some = StopSelection("x", "Pragsattel", listOf(LineDirection("U14", "Vaihingen")))
        assertEquals(listOf("U14"), deps.filter(some::matches).map { it.line })

        val all = StopSelection("x", "Pragsattel", emptyList())
        assertEquals(3, deps.count(all::matches))
    }

    @Test
    fun configAndCacheRoundTripThroughJson() {
        val config = WidgetConfig(
            listOf(
                StopSelection("5002260", "Pragsattel", listOf(LineDirection("U12", "Dürrlewang"))),
                StopSelection("5000275", "Löwentor", emptyList()),
            ),
            order = listOf(
                TileKey("5000275", null),
                TileKey("5002260", LineDirection("U12", "Dürrlewang")),
            ),
            highlight = mapOf(
                TileKey("5000275", null) to 5,
                TileKey("5002260", LineDirection("U12", "Dürrlewang")) to WidgetConfig.HIGHLIGHT_OFF,
            ),
        )
        assertEquals(config, WidgetConfig.fromJson(JSONObject(config.toJson().toString())))

        val cache = DepartureCache(Instant.ofEpochMilli(1_000), VvsParser.parseDepartures(departuresJson), null)
        assertEquals(cache, DepartureCache.fromJson(JSONObject(cache.toJson().toString())))
    }

    @Test
    fun singleStopConfigsFromOlderVersionsStillLoad() {
        val legacy = JSONObject(
            """{"stopId":"5002260","stopName":"Pragsattel","selections":[{"line":"U12","destination":"Dürrlewang"}]}""",
        )
        assertEquals(
            WidgetConfig(listOf(StopSelection("5002260", "Pragsattel", listOf(LineDirection("U12", "Dürrlewang"))))),
            WidgetConfig.fromJson(legacy),
        )
    }

    @Test
    fun highlightThresholdFallsBackFromLineToStopToDefault() {
        val u12 = LineDirection("U12", "Dürrlewang")
        val config = WidgetConfig(
            stops = emptyList(),
            highlight = mapOf(TileKey("a", u12) to 3, TileKey("b", null) to 7),
        )
        fun at(stop: String) = Departure(
            line = "U12",
            destination = "Dürrlewang",
            productClass = 3,
            planned = Instant.EPOCH,
            estimated = null,
            cancelled = false,
            platform = "",
            stopId = stop,
        )
        assertEquals(3, config.highlightFor(at("a")))
        assertEquals(7, config.highlightFor(at("b")))
        assertEquals(WidgetConfig.DEFAULT_HIGHLIGHT, config.highlightFor(at("c")))
    }

    @Test
    fun readsTripDirectionFromLineId() {
        assertEquals("H", VvsParser.directionOf("vvs:20054: :H:j25"))
        assertEquals("R", VvsParser.directionOf("vvs:20054: :R:j25"))
        assertEquals("", VvsParser.directionOf("vvs:20054"))
        assertEquals("", VvsParser.directionOf(""))
    }

    @Test
    fun tileKeysFromOlderVersionsStillDecode() {
        val legacy = TileKey.decode("5002260|U12|Dürrlewang")
        assertEquals(TileKey("5002260", LineDirection("U12", "Dürrlewang")), legacy)
        val key = TileKey("5002260", LineDirection("54", "Freiberg / Max-Eyth-See", "H"))
        assertEquals(key, TileKey.decode(key.encode()))
    }
}
