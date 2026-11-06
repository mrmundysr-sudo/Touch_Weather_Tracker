package com.mundymorning.touchweathertracker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mundymorning.touchweathertracker.TouchWeatherApp
import com.mundymorning.touchweathertracker.domain.EventMode
import com.mundymorning.touchweathertracker.source.NwsSource
import com.mundymorning.touchweathertracker.widget.WidgetUpdater

class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TouchWeatherApp
        val repo = app.repo
        // Locked app does no background work (no refresh, no notifications) until unlocked again.
        if (!repo.prefs.premiumActive) return Result.success()
        Notifier.createChannels(applicationContext)
        return try {
            AlertEngine.poll(applicationContext, repo)
            checkEventMode(repo)
            // Keep widget data fresh from the same pass (widgets show their own timestamps).
            repo.places.value.take(6).forEach { runCatching { repo.weather(it) } }
            runCatching { WidgetUpdater.updateAll(applicationContext) }
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }

    private suspend fun checkEventMode(repo: com.mundymorning.touchweathertracker.data.Repository) {
        val prefs = repo.prefs
        val storms = repo.storms(force = true).value.orEmpty()
        val hurrAlerts = runCatching { NwsSource.alertsAll().filter { it.event.startsWith("Hurricane") } }.getOrDefault(emptyList())
        val sig = EventMode.evaluate(storms, hurrAlerts)
        val active = prefs.eventModeActiveStorm
        val eligible = sig.eligibleStorm
        if (active == null) {
            if (eligible != null && prefs.eventModeOffers && eligible.id !in prefs.eventModeDeclined && eligible.id !in prefs.eventModePromptedFor) {
                prefs.eventModePromptedFor = prefs.eventModePromptedFor + eligible.id
                Notifier.postEventOffer(applicationContext, eligible.id, eligible.name, sig.reason.orEmpty())
            }
        } else {
            val activeStorm = storms.firstOrNull { it.id == active }
            val stillRelevant = eligible?.id == active || hurrAlerts.any { a -> repo.places.value.any { p -> p.countyFips != null && a.sameCodes.any { it.endsWith(p.countyFips) } } }
            if (activeStorm == null && !stillRelevant || (!stillRelevant && eligible == null)) {
                if (active !in prefs.eventModeEndPromptDismissed) Notifier.postEventEnd(applicationContext, active, activeStorm?.name ?: "The storm")
            }
        }
    }
}
