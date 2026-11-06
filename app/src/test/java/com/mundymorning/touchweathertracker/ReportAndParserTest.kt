package com.mundymorning.touchweathertracker

import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.data.Place
import com.mundymorning.touchweathertracker.domain.*
import com.mundymorning.touchweathertracker.source.*
import org.junit.Assert.*
import org.junit.Test

class ReportAndParserTest {
    @Test fun atcfCoordinates() {
        assertEquals(34.2, Atcf.coord("342N")!!, 1e-9)
        assertEquals(-71.3, Atcf.coord("713W")!!, 1e-9)
        assertEquals(-12.5, Atcf.coord("125S")!!, 1e-9)
        assertNull(Atcf.coord(""))
    }

    @Test fun atcfLineAndLatestTrack() {
        val txt = """
AL, 10, 2026102000, 03, AVNO,   0, 342N,  713W,  40, 1005, XX,  34, NEQ,  215,    0,  107,  206, 1013,  161,  74,   0,   0,
AL, 10, 2026102000, 03, AVNO,   6, 349N,  709W,  39, 1002, XX,  34, NEQ,  173,    0,  129,  171, 1010,  134,  79,   0,   0,
AL, 10, 2026101918, 03, AVNO,   0, 330N,  720W,  35, 1008, XX,  34, NEQ,  100,    0,  100,  100, 1013,  100,  70,   0,   0,
""".trimIndent()
        val t = Atcf.latestTrack(Atcf.parse(txt), "AVNO")!!
        assertEquals("2026102000", t.initDtg)
        assertEquals(2, t.points.size)
        assertEquals(34.9, t.points[1].lat, 1e-9)
    }

    @Test fun hdobParse() {
        val body = """URNT15 KWBC 061646
NOAA3 WXWXA TRAIN              HDOB 12 20261106
163700 2830N 08205W 6961 03195 0117 +082 -185 257007 010 /// /// 03
163730 2829N 08205W 6961 03195 9985 +082 -185 257045 052 048 012 03"""
        val issued = java.time.LocalDateTime.of(2026, 11, 6, 16, 46).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val h = ReconSource.parseHdob(body, issued)
        assertEquals(2, h.size)
        assertEquals(10, h[0].peakFlWindKt); assertNull(h[0].sfmrWindKt)
        assertEquals(1011.7, h[0].extrapSlpMb!!, 0.01)
        assertEquals(48, h[1].sfmrWindKt); assertEquals(998.5, h[1].extrapSlpMb!!, 0.01)
        assertEquals(28.5, h[0].lat, 0.01); assertEquals(-82.0833, h[0].lon, 0.01)
    }

    @Test fun vdmParse() {
        val txt = "URNT12 KNHC 092326\nVORTEX DATA MESSAGE   AL092026\nA. 09/22:47:30Z\nB. 29.79 deg N 086.76 deg W\nC. 700 mb 2861 m\nD. 972 mb\nE. 050 deg 18 kt\nMAX FL WIND 128 KT 068 / 16 NM 18:37:00Z\n"
        val now = java.time.LocalDateTime.of(2026, 10, 10, 1, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val v = ReconSource.parseVdm(txt, now)!!
        assertEquals("al092026", v.stormId); assertEquals(972, v.minSlpMb); assertEquals(128, v.maxFlWindKt); assertEquals(-86.76, v.lon, 1e-9)
    }

    @Test fun ndbcParseTreatsMissingAsNull() {
        val txt = "#STN LAT LON YYYY MM DD hh mm WDIR WSPD GST WVHT DPD APD MWD PRES PTDY ATMP WTMP DEWP VIS TIDE\n" +
            "42019 27.913 -95.352 2026 11 06 17 50 90 7.0 9.0 1.2 6.0 4.8 MM 1018.2 MM 24.1 25.0 MM MM MM\n" +
            "ZZZZ1 10.0 -50.0 2026 11 06 17 50 MM MM MM MM MM MM MM MM MM MM MM MM MM MM\n"
        val b = NdbcSource.parse(txt)
        assertEquals(2, b.size)
        assertEquals(15.66, b[0].windMph!!, 0.05); assertNull(b[0].waterTempF.takeIf { false }); assertEquals(77.0, b[0].waterTempF!!, 0.2)
        assertNull(b[1].windMph); assertNull(b[1].pressureInHg)
    }

    @Test fun petStatusNeverInferredFromMissing() {
        assertEquals(PetStatus.UNKNOWN, FemaSource.petStatus(null, null))
        assertEquals(PetStatus.UNKNOWN, FemaSource.petStatus(" ", null))
        assertEquals(PetStatus.ALLOWED, FemaSource.petStatus("Y", null))
        assertEquals(PetStatus.NOT_ALLOWED, FemaSource.petStatus("N", null))
        assertEquals(PetStatus.NOT_ALLOWED, FemaSource.petStatus(null, "Pets not allowed"))
        assertEquals(PetStatus.UNKNOWN, FemaSource.petStatus("X", "see staff"))
    }

    @Test fun alertLevels() {
        assertEquals(com.mundymorning.touchweathertracker.data.AlertLevel.WARNING, NwsSource.levelOf("Tornado Warning"))
        assertEquals(com.mundymorning.touchweathertracker.data.AlertLevel.WATCH, NwsSource.levelOf("Hurricane Watch"))
        assertEquals(com.mundymorning.touchweathertracker.data.AlertLevel.ADVISORY, NwsSource.levelOf("Wind Advisory"))
        assertEquals(com.mundymorning.touchweathertracker.data.AlertLevel.STATEMENT, NwsSource.levelOf("Rip Current Statement"))
        assertTrue(NwsSource.isLifeSafety("Flash Flood Warning", "Severe", null))
        assertFalse(NwsSource.isLifeSafety("Wind Advisory", "Minor", null))
    }

    @Test fun geometry() {
        val ring = listOf(listOf(-96.0, 29.0), listOf(-94.0, 29.0), listOf(-94.0, 31.0), listOf(-96.0, 31.0), listOf(-96.0, 29.0))
        assertTrue(Geo.inRing(30.0, -95.0, ring)); assertFalse(Geo.inRing(32.0, -95.0, ring))
        val d = Geo.minDistanceToPath(30.0, -95.0, listOf(25.0 to -95.0, 35.0 to -95.0))!!
        assertTrue(d.first < 1.0)
    }

    @Test fun stormReportOmitsMissingAndLabelsKinds() {
        val place = Place("p", "Pensacola", 30.42, -87.22, timeZone = "America/Chicago")
        val storm = ActiveStorm("al092026", "AT4", "Isaias", "HU", 100, 960, 28.5, -87.0, 350, 10, 1L, "20", 1L, "https://www.nhc.noaa.gov/text/MIATCPAT4.shtml", null, null, null, null)
        val fixes = listOf(
            AtcfFix("AL", 9, "2026100900", "OFCL", 0, 28.5, -87.0, 100, 960, null),
            AtcfFix("AL", 9, "2026100900", "OFCL", 24, 30.4, -87.5, 90, 965, null),
        )
        val rep = StormReportBuilder.build(StormInputs(place, storm, Atcf.latestTrack(fixes, "OFCL"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, null))
        val all = rep.sections.flatMap { it.items }
        assertTrue(all.any { it.kind == Kind.OBSERVED && it.source == "NHC advisory" })
        assertTrue(all.any { it.kind == Kind.FORECAST && it.text.contains("closest approach") })
        // With no buoys / gauges / recon supplied there are no invented observations.
        assertTrue(rep.sections.first { it.title.startsWith("What nearby") }.items.isEmpty())
        assertTrue(rep.threatens)
        assertEquals(115, StormReportBuilder.ktToMph(100))
    }
}
