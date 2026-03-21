package moe.radar.mihon_gateway.state

import eu.kanade.tachiyomi.source.CatalogueSource
import suwayomi.tachidesk.manga.impl.extension.github.OnlineExtension
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/**
 * Stateless in-memory storage for extension and source metadata.
 * No database required!
 */
object StatelessState {
    /**
     * Extension metadata cache
     * Key: package name (e.g., "eu.kanade.tachiyomi.extension.en.asurascans")
     */
    val extensions = ConcurrentHashMap<String, ExtensionMetadata>()

    /**
     * Source metadata cache
     * Key: source ID (from the extension)
     */
    val sources = ConcurrentHashMap<Long, SourceMetadata>()

    /**
     * Loaded source instances (per-source OkHttp client with rate limiting)
     * Key: source ID
     */
    val loadedSources = ConcurrentHashMap<Long, CatalogueSource>()

    /**
     * JAR file paths for loaded extensions
     * Key: package name
     */
    val loadedJars = ConcurrentHashMap<String, String>()

    /**
     * Extension list from GitHub - cached for 60 seconds
     */
    var extensionListCache: List<OnlineExtension>? = null
    var extensionListCacheTime: Long = 0
    private const val CACHE_TTL_MS = 60_000L // 60 seconds

    fun isExtensionListCacheValid(): Boolean {
        return extensionListCache != null &&
            (System.currentTimeMillis() - extensionListCacheTime) < CACHE_TTL_MS
    }

    fun updateExtensionListCache(extensions: List<OnlineExtension>) {
        extensionListCache = extensions
        extensionListCacheTime = System.currentTimeMillis()
    }

    /**
     * Clear all state (useful for testing)
     */
    fun clear() {
        extensions.clear()
        sources.clear()
        loadedSources.clear()
        loadedJars.clear()
        extensionListCache = null
        extensionListCacheTime = 0
    }
}

@Serializable
data class ExtensionMetadata(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val lang: String,
    val isNsfw: Boolean,
    val isInstalled: Boolean,
    val hasUpdate: Boolean,
    val iconUrl: String,
    val repo: String?,
    val apkName: String,
    val classFQName: String
)

@Serializable
data class SourceMetadata(
    val id: Long,
    val name: String,
    val lang: String,
    val extensionPkgName: String, // Which extension owns this source
    val isNsfw: Boolean
)
