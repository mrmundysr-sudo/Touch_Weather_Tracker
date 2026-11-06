package com.mundymorning.touchweathertracker

import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.data.Place
import com.mundymorning.touchweathertracker.source.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URL

/**
 * Integration checks against the real public services. They are skipped (not failed) when api.weather.gov is unreachable,
 * so an offline build never reports a false pass or false failure. Results are printed for the work report.
 */
class LiveSourceTest {
    @Before fun setUp() {
        Http.initWith(File(System.getProperty("java.io.tmpdir"), "twt-test-http"))
        val ok = try { (URL("https://api.weather.gov/").openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }).getInputStream().close(); true } catch (_: Exception) { false }
        assumeTrue("network unavailable", ok)
    }

    @Test fun nwsPointForecastAndConditions() = runBlocking {
        val place = Place("t", "Houston", 29.76, -95.37)
        val (w, pt) = NwsSource.weather(place, null)
        println("NWS ${pt.gridId} ${pt.state} ${pt.city} county=${pt.countyFips} tz=${pt.timeZone}")
        println("cond=${w.conditions} hourly=${w.hourly.size} daily=${w.daily.size}")
        assert(w.hourly.size > 24); assert(w.daily.size >= 6); assert(pt.countyFips == "48201")
    }

    @Test fun nwsAlertsNationwide() = runBlocking {
        val a = NwsSource.alertsAll()
        println("alerts nationwide=${a.size} sample=${a.take(3).map { it.event + "/" + it.level + "/" + it.states }}")
        assert(a.all { it.id.isNotBlank() })
    }

    @Test fun census() = runBlocking {
        val z = CensusSource.search("77002"); println("zip=$z"); assert(z.isNotEmpty())
        val c = CensusSource.search("Houston, TX"); println("city=${c.take(3)}"); assert(c.isNotEmpty())
        val a = CensusSource.search("1600 Pennsylvania Ave NW Washington DC"); println("addr=${a.take(1)}"); assert(a.isNotEmpty())
        val co = CensusSource.counties("FL"); println("fl counties=${co.size}"); assert(co.size > 60)
    }

    @Test fun nhcAtcfOnArchivedStorm() = runBlocking {
        val fixes = NhcSource.aDeck("al102026")
        val ofcl = Atcf.latestTrack(fixes, "OFCL"); val gfs = Atcf.latestTrack(fixes, "AVNO")
        println("OFCL=${ofcl?.initDtg} pts=${ofcl?.points?.size}  GFS=${gfs?.initDtg} pts=${gfs?.points?.size}")
        assert(ofcl != null && gfs != null)
        val best = NhcSource.bestTrack("al102026"); println("best pts=${best.size}"); assert(best.size > 5)
    }

    @Test fun nhcActive() = runBlocking {
        val s = NhcSource.activeStorms(); println("active storms=${s.size}")
    }

    @Test fun buoysAndWaterLevels() = runBlocking {
        val b = NdbcSource.nearby(29.2, -94.9, 250.0, 720, 3); println("buoys=${b.map { it.id + " " + it.distanceMi.toInt() + "mi wind=" + it.windMph + " wave=" + it.waveHeightFt }}")
        assert(b.isNotEmpty())
        val w = CoopsSource.nearby(29.31, -94.79, 60.0, 3, 180); println("water=${w.map { it.name + " " + it.levelFtMllw + " dep=" + it.departureFt }}")
    }

    @Test fun fema() = runBlocking {
        val sh = FemaSource.shelters(30.4, -86.5, 300.0); println("shelters=${sh.size} pets=${sh.groupBy { it.pet }.mapValues { it.value.size }}")
        val drc = FemaSource.recoveryCenters(30.4, -86.5, 300.0); println("drc=${drc.size}")
        val d = FemaSource.declarations("FL", "12005"); println("decl=${d.take(3).map { it.declarationString + " " + it.title + " " + it.area }}")
        assert(d.isNotEmpty())
    }

    @Test fun sheltersWhereTheyExist() = runBlocking {
        val sh = FemaSource.shelters(36.79, -108.69, 150.0)
        println("NM shelters=${sh.size} ${sh.take(3).map { it.name + " " + it.distanceMi.toInt() + "mi " + it.status + " pet=" + it.pet + " phone=" + it.phone }}")
        assert(sh.isNotEmpty())
    }

    @Test fun utilityAndOutage() = runBlocking {
        val u = UtilitySource.at(29.76, -95.37); println("utility=${u.map { it.name + " " + it.phone }}"); assert(u.isNotEmpty())
        val o = MassOutageSource.county("48201"); println("outage=$o"); assert(o != null)
    }

    @Test fun reconProducts() = runBlocking {
        val r = ReconSource.summary("al092026", 29.8, -86.8, hours = 24 * 400, radiusKm = 5000.0)
        println("recon=$r")
    }

    @Test fun statesCrossedOverGulf() = runBlocking {
        val over = CensusSource.statesCrossed(listOf(25.0 to -90.0, 27.0 to -90.0))
        val landfall = CensusSource.statesCrossed(listOf(27.0 to -90.0, 31.0 to -91.0))
        println("gulf-only=$over landfall=$landfall")
        assert(over.isEmpty()); assert("LA" in landfall)
    }
}
