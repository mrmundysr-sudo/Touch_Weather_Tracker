package com.mundymorning.touchweathertracker.domain

import com.mundymorning.touchweathertracker.data.Alert
import com.mundymorning.touchweathertracker.source.ActiveStorm
import com.mundymorning.touchweathertracker.source.Atcf
import com.mundymorning.touchweathertracker.source.NhcSource

/** Result of evaluating the temporary hurricane event-mode trigger from the latest OFFICIAL NHC forecast. */
data class EventSignal(val eligibleStorm: ActiveStorm?, val reason: String?, val hurricaneWarningInEffect: Boolean)

object EventMode {
    /**
     * Eligible when NHC's official forecast (ATCF OFCL track, not a GFS/ECMWF run) has the center reach a U.S. state within 72 h
     * while the storm is forecast at hurricane strength (>= 64 kt) at/near that point, or when a U.S. Hurricane Warning/Watch
     * from NWS/NHC is already in effect.
     */
    suspend fun evaluate(storms: List<ActiveStorm>, nationalHurricaneAlerts: List<Alert>): EventSignal {
        val warned = nationalHurricaneAlerts.any { it.event.equals("Hurricane Warning", true) || it.event.equals("Hurricane Watch", true) }
        for (s in storms) {
            val fixes = runCatching { NhcSource.aDeck(s.id) }.getOrNull() ?: continue
            val ofcl = Atcf.latestTrack(fixes, "OFCL") ?: continue
            val upTo72 = ofcl.points.filter { it.tauHours <= 72 }
            if (upTo72.size < 2) continue
            // Hurricane-strength forecast during the window.
            val hurricaneAt = upTo72.indexOfLast { (it.vmaxKt ?: 0) >= 64 }
            if (hurricaneAt < 0) continue
            val states = runCatching { com.mundymorning.touchweathertracker.source.CensusSource.statesCrossed(upTo72.map { it.lat to it.lon }) }.getOrNull().orEmpty()
            if (states.isNotEmpty()) {
                return EventSignal(s, "NHC's official forecast shows ${s.name} at hurricane strength reaching ${states.joinToString(", ")} within 72 hours.", warned)
            }
        }
        if (warned) {
            val s = storms.firstOrNull { it.isHurricaneStrength } ?: storms.firstOrNull()
            return EventSignal(s, "A Hurricane Watch or Warning is in effect for part of the U.S.", true)
        }
        return EventSignal(null, null, false)
    }
}
