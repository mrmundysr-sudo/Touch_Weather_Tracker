package com.mundymorning.touchweathertracker.work

import android.content.Context
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.source.NwsSource

/**
 * Polls NWS for places/areas the user opted in to, de-duplicates, and notifies only for NEW alerts,
 * material updates (different expiry/text/type) and early endings. Never notifies on every poll, and never applies quiet hours
 * to a new severe alert.
 */
object AlertEngine {
    private data class Target(val label: String, val prefs: AlertPrefs, val alerts: List<Alert>)

    fun wants(prefs: AlertPrefs, a: Alert): Boolean {
        if (!prefs.notify) return false
        return when (a.level) {
            AlertLevel.WARNING -> prefs.warnings
            AlertLevel.WATCH -> prefs.watches
            AlertLevel.ADVISORY -> prefs.advisories
            AlertLevel.STATEMENT -> prefs.statements || (a.lifeSafety && prefs.warnings)
        }
    }

    fun signature(a: Alert): String = listOf(a.messageType, a.endMs?.toString().orEmpty(), (a.headline ?: "").hashCode(), a.description.hashCode(), a.instruction.orEmpty().hashCode()).joinToString("|")

    suspend fun poll(ctx: Context, repo: Repository): Int {
        val prefs = repo.prefs
        val targets = mutableListOf<Target>()
        var anySuccess = false
        for (p in repo.places.value.filter { it.alertPrefs.notify }) {
            runCatching { NwsSource.alertsForPoint(p.lat, p.lon) }.onSuccess { anySuccess = true; targets += Target(p.name, p.alertPrefs, it) }
        }
        for (f in repo.favorites.value.filter { it.alertPrefs.notify }) {
            runCatching { repo.alertsForFavorite(f) }.onSuccess { anySuccess = true; targets += Target(f.label, f.alertPrefs, it) }
        }
        if (!anySuccess) return 0
        var posted = 0
        val seen = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        // Highest-priority label wins when overlapping targets match the same alert.
        for (t in targets) for (a in t.alerts) {
            if (!wants(t.prefs, a)) continue
            val endMs = a.endMs
            if (endMs != null && endMs < now) continue
            if (!seen.add(a.id)) continue
            val sig = signature(a)
            val prev = prefs.notifiedSignature(a.id)
            val storedValue = "$sig#${a.endMs ?: 0L}#${a.event}#${t.label}"
            if (prev == null) { Notifier.postAlert(ctx, a, t.label, update = false); posted++ }
            else if (prev.substringBefore('#') != sig) { Notifier.postAlert(ctx, a, t.label, update = true); posted++ }
            prefs.setNotified(a.id, storedValue)
        }
        // Early endings: previously notified, not in any active list now, and not yet past its own end time.
        for ((id, v) in prefs.notifiedAll()) {
            if (id in seen) continue
            val parts = v.split('#')
            val end = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            if (end > now + 5 * 60_000L) Notifier.postEnded(ctx, parts.getOrNull(2).orEmpty(), parts.getOrNull(3).orEmpty())
            prefs.forgetNotified(id)
        }
        return posted
    }
}
