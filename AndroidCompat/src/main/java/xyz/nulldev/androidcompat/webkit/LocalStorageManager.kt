package xyz.nulldev.androidcompat.webkit

import android.content.Context
import android.content.SharedPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI

/**
 * Singleton per-origin key-value store that backs `window.localStorage`
 * for the headless WebView provider. Data is persisted via SharedPreferences.
 */
object LocalStorageManager : KoinComponent {
    private val context: Context by inject()
    private val prefsCache = mutableMapOf<String, SharedPreferences>()

    private fun prefsFor(origin: String): SharedPreferences {
        return synchronized(prefsCache) {
            prefsCache.getOrPut(origin) {
                context.getSharedPreferences("localStorage_${origin.hashCode()}", Context.MODE_PRIVATE)
            }
        }
    }

    fun extractOrigin(url: String): String {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return url
            val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            url
        }
    }

    fun setItem(origin: String, key: String, value: String) {
        prefsFor(origin).edit().putString(key, value).apply()
    }

    fun getItem(origin: String, key: String): String? {
        return prefsFor(origin).getString(key, null)
    }

    fun removeItem(origin: String, key: String) {
        prefsFor(origin).edit().remove(key).apply()
    }

    fun clear(origin: String) {
        prefsFor(origin).edit().clear().apply()
    }

    fun getAllItems(origin: String): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return prefsFor(origin).all
            .filterValues { it is String }
            .mapValues { it.value as String }
    }
}
