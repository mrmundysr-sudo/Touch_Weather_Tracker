package com.mundymorning.touchweathertracker

import android.app.Application
import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.billing.Monetization
import com.mundymorning.touchweathertracker.data.Repository
import com.mundymorning.touchweathertracker.work.Notifier

class TouchWeatherApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var monetization: Monetization
        private set

    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        repo = Repository(this)
        monetization = Monetization(this, repo.prefs)
        Notifier.createChannels(this)
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = Http.USER_AGENT
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid").apply { mkdirs() }
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles").apply { mkdirs() }
            tileFileSystemCacheMaxBytes = 80L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 60L * 1024 * 1024
        }
        instance = this
    }

    companion object {
        lateinit var instance: TouchWeatherApp
            private set
    }
}
