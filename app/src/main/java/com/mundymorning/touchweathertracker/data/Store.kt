package com.mundymorning.touchweathertracker.data

import android.content.Context
import com.mundymorning.touchweathertracker.core.Http
import kotlinx.serialization.KSerializer
import java.io.File

/** Tiny atomic JSON file store. Used for places, caches and small state (no database required). */
class Store(context: Context) {
    private val dir = File(context.filesDir, "store").apply { mkdirs() }

    private fun file(name: String) = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    @Synchronized
    fun <T> read(name: String, ser: KSerializer<T>): T? = try {
        val f = file(name)
        if (f.exists()) Http.json.decodeFromString(ser, f.readText()) else null
    } catch (_: Exception) { null }

    @Synchronized
    fun <T> write(name: String, ser: KSerializer<T>, value: T) {
        val f = file(name)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(Http.json.encodeToString(ser, value))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized
    fun delete(name: String) { file(name).delete() }
}
