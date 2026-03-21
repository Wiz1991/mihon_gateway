package moe.radar.mihon_gateway.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import moe.radar.mihon_gateway.state.SourceMetadata
import moe.radar.mihon_gateway.state.StatelessState

/**
 * Stateless source manager - manages loaded sources in memory.
 */
object SourceManager {
    private val logger = KotlinLogging.logger {}

    /**
     * Get a catalogue source by ID
     */
    fun getCatalogueSource(sourceId: Long): CatalogueSource? {
        val cached = StatelessState.loadedSources[sourceId]
        if (cached != null) {
            return cached
        }

        logger.warn { "Source $sourceId not found in cache" }
        return null
    }

    /**
     * Get a catalogue source or throw exception
     */
    fun getCatalogueSourceOrThrow(sourceId: Long): CatalogueSource {
        return getCatalogueSource(sourceId)
            ?: throw IllegalArgumentException("Source $sourceId not found. Is the extension installed?")
    }

    /**
     * List all loaded sources
     */
    fun listSources(): List<SourceInfo> {
        return StatelessState.loadedSources.map { (id, source) ->
            val metadata = StatelessState.sources[id]

            SourceInfo(
                id = id,
                name = source.name,
                lang = source.lang,
                supportsLatest = source.supportsLatest,
                isConfigurable = source is ConfigurableSource,
                isNsfw = metadata?.isNsfw ?: false,
                displayName = source.toString(),
                baseUrl = (source as? HttpSource)?.baseUrl,
                extensionPkgName = metadata?.extensionPkgName
            )
        }
    }

    /**
     * Get source info by ID
     */
    fun getSourceInfo(sourceId: Long): SourceInfo? {
        val source = StatelessState.loadedSources[sourceId] ?: return null
        val metadata = StatelessState.sources[sourceId]

        return SourceInfo(
            id = sourceId,
            name = source.name,
            lang = source.lang,
            supportsLatest = source.supportsLatest,
            isConfigurable = source is ConfigurableSource,
            isNsfw = metadata?.isNsfw ?: false,
            displayName = source.toString(),
            baseUrl = (source as? HttpSource)?.baseUrl,
            extensionPkgName = metadata?.extensionPkgName
        )
    }

    /**
     * Check if a source is loaded
     */
    fun isSourceLoaded(sourceId: Long): Boolean {
        return StatelessState.loadedSources.containsKey(sourceId)
    }

    /**
     * Get sources by extension package name
     */
    fun getSourcesByExtension(pkgName: String): List<SourceInfo> {
        return StatelessState.sources
            .filter { it.value.extensionPkgName == pkgName }
            .mapNotNull { (sourceId, _) -> getSourceInfo(sourceId) }
    }
}

/**
 * Source information data class
 */
data class SourceInfo(
    val id: Long,
    val name: String,
    val lang: String,
    val supportsLatest: Boolean,
    val isConfigurable: Boolean,
    val isNsfw: Boolean,
    val displayName: String,
    val baseUrl: String?,
    val extensionPkgName: String?
)
