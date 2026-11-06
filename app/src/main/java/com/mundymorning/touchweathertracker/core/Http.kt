package com.mundymorning.touchweathertracker.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown for any failed upstream call so UI can show an honest message. */
class SourceException(val source: String, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One shared HTTP client. All upstream sources are free public services; we identify the app in the
 * User-Agent (required by api.weather.gov) and never send personal data.
 */
object Http {
    const val USER_AGENT = "TouchWeatherTracker/0.1 (Android; github.com/mrmundysr-sudo/Touch_Weather_Tracker)"

    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

    @Volatile private var client: OkHttpClient? = null

    fun init(context: Context) = initWith(context.cacheDir)

    fun initWith(cacheDir: File) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .cache(Cache(File(cacheDir, "http"), 20L * 1024 * 1024))
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", chain.request().header("Accept") ?: "application/geo+json, application/json, text/plain, */*")
                            .build()
                    )
                }
                .build()
        }
    }

    fun client(): OkHttpClient = client ?: error("Http.init(context) not called")

    /** GET returning the body as text. Retries once on 5xx / timeouts. */
    suspend fun text(source: String, url: String, accept: String? = null): String = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url.toHttpUrl()).apply { if (accept != null) header("Accept", accept) }.build()
                client().newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return@withContext resp.body?.string().orEmpty()
                    if (resp.code in 500..599 && attempt == 0) {
                        last = SourceException(source, "HTTP ${resp.code}")
                    } else {
                        throw SourceException(source, "HTTP ${resp.code} from $source")
                    }
                }
            } catch (e: SourceException) { throw e
            } catch (e: IOException) { last = e }
            if (attempt == 0) kotlinx.coroutines.delay(600)
        }
        throw SourceException(source, "Could not reach $source", last)
    }

    suspend fun bytes(source: String, url: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url.toHttpUrl()).build()
            client().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw SourceException(source, "HTTP ${resp.code} from $source")
                resp.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: SourceException) { throw e
        } catch (e: IOException) { throw SourceException(source, "Could not reach $source", e) }
    }

    suspend fun jsonElement(source: String, url: String): JsonElement =
        try { json.parseToJsonElement(text(source, url)) }
        catch (e: SourceException) { throw e }
        catch (e: Exception) { throw SourceException(source, "Unreadable response from $source", e) }

    suspend inline fun <reified T> get(source: String, url: String): T =
        try { json.decodeFromString<T>(text(source, url)) }
        catch (e: SourceException) { throw e }
        catch (e: Exception) { throw SourceException(source, "Unreadable response from $source", e) }
}
