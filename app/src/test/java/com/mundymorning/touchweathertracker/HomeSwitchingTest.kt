package com.mundymorning.touchweathertracker

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mundymorning.touchweathertracker.data.Repository
import com.mundymorning.touchweathertracker.ui.AppViewModel
import com.mundymorning.touchweathertracker.widget.WidgetKit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real repository, view model and widget data loader (no UI) for switching Home back and forth. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeSwitchingTest {
    private val app get() = ApplicationProvider.getApplicationContext<TouchWeatherApp>()

    @Test fun switchingHomeBackAndForthUpdatesAppSelectionAndWidgetData() = runBlocking {
        val repo = app.repo
        val a = repo.addPlace("162 N Main St", 44.26, -72.57, null)      // first place becomes Home
        val b = repo.addPlace("500 Elm St", 29.76, -95.37, null)
        assertTrue(a.isHome); assertFalse(b.isHome)

        val vm = AppViewModel(app)
        fun assertHome(id: String) {
            assertEquals("repo home", id, repo.home()?.id)
            assertEquals("repo selected (what Today refreshes)", id, repo.selected()?.id)
            assertEquals("widget data", id, WidgetKit.load(app).place?.id)
            assertEquals("exactly one Home", 1, repo.places.value.count { it.isHome })
            assertEquals("persisted", id, Repository(app).home()?.id)
        }
        vm.makeHome(b.id); shadowOf(Looper.getMainLooper()).idle()
        assertHome(b.id); assertEquals("view model selection", b.id, vm.selected.value?.id)

        vm.makeHome(a.id); shadowOf(Looper.getMainLooper()).idle()
        assertHome(a.id); assertEquals(a.id, vm.selected.value?.id)

        vm.makeHome(b.id); shadowOf(Looper.getMainLooper()).idle()    // and again: it must keep switching, not stick
        assertHome(b.id)
    }

    @Test fun browsingAnotherPlaceDoesNotMoveHomeOrWidgets() = runBlocking {
        val repo = app.repo
        val a = repo.addPlace("Home Place", 44.26, -72.57, null)
        val b = repo.addPlace("Other Place", 29.76, -95.37, null)
        val vm = AppViewModel(app)
        vm.selectPlace(b.id); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(b.id, repo.selected()?.id)
        assertEquals("Home unchanged", a.id, repo.home()?.id)
        assertEquals("widgets keep showing Home", a.id, WidgetKit.load(app).place?.id)
    }

    @Test fun removingHomePromotesAnotherPlaceAndWidgetsFollow() = runBlocking {
        val repo = app.repo
        val a = repo.addPlace("First", 44.26, -72.57, null)
        val b = repo.addPlace("Second", 29.76, -95.37, null)
        val vm = AppViewModel(app)
        vm.removePlace(a.id); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(b.id, repo.home()?.id)
        assertEquals(b.id, WidgetKit.load(app).place?.id)
    }

    @Test fun sameCityPlacesGetDistinctWidgetLabelsAndDifferentCitiesGetCityState() = runBlocking {
        val repo = app.repo
        val a = repo.addPlace("162 N Main St", 44.26, -72.57, null)
        val b = repo.addPlace("500 Elm St", 44.27, -72.58, null)
        repo.updatePlace(repo.places.value.first { it.id == a.id }.copy(city = "Montpelier", state = "VT"))
        repo.updatePlace(repo.places.value.first { it.id == b.id }.copy(city = "Montpelier", state = "VT"))
        assertEquals("162 N Main St", WidgetKit.load(app).label)          // same city → saved name keeps the switch visible
        repo.setHome(b.id)
        assertEquals("500 Elm St", WidgetKit.load(app).label)
        repo.updatePlace(repo.places.value.first { it.id == b.id }.copy(city = "Barre"))
        assertEquals("Barre, VT", WidgetKit.load(app).label)              // different cities → "City, ST"
    }
}
