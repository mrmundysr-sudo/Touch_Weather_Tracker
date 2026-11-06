package com.mundymorning.touchweathertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.mundymorning.touchweathertracker.BuildConfig

/**
 * Banner ad slot. It sits in normal layout flow (never overlaid) and is only placed on screens that carry no emergency content:
 * the caller must not show it while an active warning applies or on Alerts / Storms / Resources / Map.
 */
@Composable
fun AdBanner(canRequest: Boolean, modifier: Modifier = Modifier) {
    if (!canRequest) return
    val ctx = LocalContext.current
    val width = LocalConfiguration.current.screenWidthDp
    val adView = remember { AdView(ctx).apply { setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, width)); adUnitId = BuildConfig.ADMOB_BANNER_UNIT; loadAd(AdRequest.Builder().build()) } }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("Advertisement", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(factory = { adView })
    }
}
