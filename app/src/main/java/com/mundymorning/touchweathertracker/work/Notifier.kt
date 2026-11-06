package com.mundymorning.touchweathertracker.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mundymorning.touchweathertracker.MainActivity
import com.mundymorning.touchweathertracker.R
import com.mundymorning.touchweathertracker.data.Alert
import com.mundymorning.touchweathertracker.data.AlertLevel

object Notifier {
    const val CH_URGENT = "alerts_urgent"
    const val CH_WARNING = "alerts_warnings"
    const val CH_WATCH = "alerts_watches"
    const val CH_MINOR = "alerts_minor"
    const val CH_EVENT = "event_mode"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, desc: String, imp: Int) = NotificationChannel(id, name, imp).apply { description = desc; if (imp >= NotificationManager.IMPORTANCE_HIGH) enableVibration(true) }
        nm.createNotificationChannels(listOf(
            ch(CH_URGENT, "Urgent life-safety alerts", "Tornado, flash flood, hurricane and other urgent official warnings for your places.", NotificationManager.IMPORTANCE_HIGH),
            ch(CH_WARNING, "Warnings", "New official NWS warnings for places you chose.", NotificationManager.IMPORTANCE_HIGH),
            ch(CH_WATCH, "Watches", "Official NWS watches for places where you opted in.", NotificationManager.IMPORTANCE_DEFAULT),
            ch(CH_MINOR, "Advisories & statements", "Lower-severity notices for places where you opted in.", NotificationManager.IMPORTANCE_LOW),
            ch(CH_EVENT, "Hurricane event mode", "Prompts about temporary hurricane event mode.", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    fun canPost(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun openApp(ctx: Context, extras: Intent.() -> Unit, req: Int): PendingIntent =
        PendingIntent.getActivity(ctx, req, Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP; extras() }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun channelFor(a: Alert): String = when {
        a.lifeSafety -> CH_URGENT
        a.level == AlertLevel.WARNING -> CH_WARNING
        a.level == AlertLevel.WATCH -> CH_WATCH
        else -> CH_MINOR
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun postAlert(ctx: Context, a: Alert, label: String, update: Boolean) {
        if (!canPost(ctx)) return
        val title = (if (update) "Updated: " else "") + a.event + (if (label.isNotBlank()) " — $label" else "")
        val body = (a.headline ?: a.areaDesc).take(240)
        val n = NotificationCompat.Builder(ctx, channelFor(a))
            .setSmallIcon(R.drawable.ic_stat_weather).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + (a.instruction?.let { "\n\n" + it.take(300) } ?: "")))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true).setOnlyAlertOnce(false)
            .setPriority(if (a.level == AlertLevel.WARNING || a.lifeSafety) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(ctx, { putExtra(MainActivity.EXTRA_ALERT_ID, a.id) }, a.id.hashCode()))
            .setGroup("alerts").build()
        NotificationManagerCompat.from(ctx).notify(a.id.hashCode(), n)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun postEnded(ctx: Context, event: String, label: String) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_MINOR).setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle("$event ended or was replaced").setContentText(if (label.isNotBlank()) "$label — check Alerts for the current status." else "Check Alerts for the current status.")
            .setAutoCancel(true).setContentIntent(openApp(ctx, { putExtra(MainActivity.EXTRA_TAB, "alerts") }, 7)).build()
        NotificationManagerCompat.from(ctx).notify(("end$event$label").hashCode(), n)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun postEventOffer(ctx: Context, stormId: String, stormName: String, reason: String) {
        if (!canPost(ctx)) return
        fun act(action: String, req: Int) = PendingIntent.getBroadcast(ctx, req, Intent(ctx, EventActionReceiver::class.java).setAction(action).putExtra("storm", stormId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_EVENT).setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle("Hurricane $stormName may reach the U.S.")
            .setContentText("Event mode can help this app refresh storm info and urgent alerts more often. Delivery is never guaranteed.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\n\nEvent mode refreshes your saved places' storm information and urgent app alerts more often during this storm. Android limits background work, so delivery is never instant or guaranteed — keep Wireless Emergency Alerts on."))
            .addAction(0, "Enable for this event", act(EventActionReceiver.ACTION_ENABLE, 1))
            .addAction(0, "Not now", act(EventActionReceiver.ACTION_DECLINE, 2))
            .addAction(0, "Details", openApp(ctx, { putExtra(MainActivity.EXTRA_TAB, "storms"); putExtra(MainActivity.EXTRA_EVENT_STORM, stormId) }, 3))
            .setAutoCancel(true).setContentIntent(openApp(ctx, { putExtra(MainActivity.EXTRA_TAB, "storms"); putExtra(MainActivity.EXTRA_EVENT_STORM, stormId) }, 3)).build()
        NotificationManagerCompat.from(ctx).notify(("offer$stormId").hashCode(), n)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun postEventEnd(ctx: Context, stormId: String, stormName: String) {
        if (!canPost(ctx)) return
        fun act(action: String, req: Int) = PendingIntent.getBroadcast(ctx, req, Intent(ctx, EventActionReceiver::class.java).setAction(action).putExtra("storm", stormId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_EVENT).setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle("$stormName is no longer expected to affect your places")
            .setContentText("Return to normal refresh settings, or keep event mode on?")
            .addAction(0, "Return to normal", act(EventActionReceiver.ACTION_END, 4))
            .addAction(0, "Keep event mode", act(EventActionReceiver.ACTION_KEEP, 5))
            .setAutoCancel(true).setContentIntent(openApp(ctx, { putExtra(MainActivity.EXTRA_TAB, "storms") }, 6)).build()
        NotificationManagerCompat.from(ctx).notify(("end$stormId").hashCode(), n)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun postTest(ctx: Context): Boolean {
        if (!canPost(ctx)) return false
        createChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_MINOR).setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle("Test notification").setContentText("Notifications from Touch Weather Tracker are working. This is not a weather alert.").build()
        NotificationManagerCompat.from(ctx).notify(9001, n)
        return true
    }

    fun cancel(ctx: Context, key: String) = NotificationManagerCompat.from(ctx).cancel(key.hashCode())
}
