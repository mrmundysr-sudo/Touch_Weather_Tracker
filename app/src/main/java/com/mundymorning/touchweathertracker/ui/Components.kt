package com.mundymorning.touchweathertracker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.AlertLevel
import com.mundymorning.touchweathertracker.domain.Kind
import com.mundymorning.touchweathertracker.source.PetStatus

fun Context.openUrl(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
fun Context.dial(phone: String) { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
fun Context.navigateTo(lat: Double, lon: Double, label: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

data class SevStyle(val bg: Color, val fg: Color, val icon: ImageVector, val label: String)

@Composable
fun sevStyle(level: AlertLevel): SevStyle {
    val c = Tw.colors
    return when (level) {
        AlertLevel.WARNING -> SevStyle(c.warning, c.onWarning, Icons.Filled.Warning, "WARNING")
        AlertLevel.WATCH -> SevStyle(c.watch, c.onWatch, Icons.Filled.Visibility, "WATCH")
        AlertLevel.ADVISORY -> SevStyle(c.advisory, c.onAdvisory, Icons.Filled.Info, "ADVISORY")
        AlertLevel.STATEMENT -> SevStyle(c.statement, c.onStatement, Icons.Filled.Description, "STATEMENT")
    }
}

@Composable
fun SeverityChip(level: AlertLevel, modifier: Modifier = Modifier) {
    val s = sevStyle(level)
    Row(modifier.clip(RoundedCornerShape(50)).background(s.bg).padding(horizontal = 10.dp, vertical = 4.dp).semantics { contentDescription = "Severity: ${s.label.lowercase()}" },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(s.icon, null, tint = s.fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(s.label, color = s.fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun KindChip(kind: Kind, modifier: Modifier = Modifier) {
    val c = Tw.colors
    val (col, icon) = when (kind) {
        Kind.OBSERVED -> c.observed to Icons.Outlined.Sensors
        Kind.FORECAST -> c.forecast to Icons.Outlined.Timeline
        Kind.WARNING -> c.warning to Icons.Outlined.Warning
        Kind.MODEL -> c.model to Icons.Outlined.Science
        Kind.SUMMARY -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Outlined.Article
    }
    Row(modifier.clip(RoundedCornerShape(50)).border(col).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = col, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
        Text(kind.label, color = col, style = MaterialTheme.typography.labelSmall)
    }
}

private fun Modifier.border(c: Color) = this.then(Modifier.background(c.copy(alpha = 0.10f)))

@Composable
fun Section(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            action?.invoke()
        }
        content()
    }
}

@Composable
fun TwCard(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surface, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val base = Modifier.fillMaxWidth().clip(shape)
    val m = if (onClick != null) base.clickable(onClick = onClick) else base
    Card(modifier = modifier.then(m), shape = shape, colors = CardDefaults.cardColors(containerColor = container), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SourceLine(source: String, updatedMs: Long?, tz: String? = null, modifier: Modifier = Modifier, stale: Boolean = false) {
    val txt = buildString {
        append("Source: $source")
        if (updatedMs != null) append(" · updated ${Units.clock(updatedMs, tz)} (${Units.ago(updatedMs)})")
        if (stale) append(" · NOT CURRENT")
    }
    Text(txt, style = MaterialTheme.typography.bodySmall, color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun StaleBanner(message: String, fetchedAtMs: Long?, tz: String?, onRetry: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Showing saved information — not current", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("$message" + (fetchedAtMs?.let { " Last updated ${Units.stamp(it, tz)}." } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text("Couldn't load this", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) { Spacer(Modifier.height(8.dp)); FilledTonalButton(onClick = onRetry) { Text("Try again") } }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (action != null) { Spacer(Modifier.height(12.dp)); action() }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, label: String = "Loading…") {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(); Spacer(Modifier.height(10.dp)); Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pet-accommodation indicator exactly as specified: allowed = colored icon, not allowed = grey icon with red prohibition mark, unknown = greyed icon with no mark. */
@Composable
fun PetBadge(status: PetStatus, modifier: Modifier = Modifier) {
    val desc = when (status) { PetStatus.ALLOWED -> "Pets allowed"; PetStatus.NOT_ALLOWED -> "Pets not allowed"; PetStatus.UNKNOWN -> "Pet policy not reported" }
    Box(modifier.size(36.dp).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        when (status) {
            PetStatus.ALLOWED -> Row { Icon(Icons.Filled.Pets, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(22.dp)) }
            PetStatus.NOT_ALLOWED -> {
                Icon(Icons.Filled.Pets, null, tint = Color(0xFF9E9E9E), modifier = Modifier.size(22.dp))
                Icon(Icons.Filled.DoNotDisturbAlt, null, tint = Color(0xFFD32F2F), modifier = Modifier.size(34.dp))
            }
            PetStatus.UNKNOWN -> Icon(Icons.Filled.Pets, null, tint = Color(0xFFBDBDBD).copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun LinkButton(text: String, url: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.OpenInNew) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = { ctx.openUrl(url) }, modifier = modifier.heightIn(min = 48.dp)) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text) }
}
