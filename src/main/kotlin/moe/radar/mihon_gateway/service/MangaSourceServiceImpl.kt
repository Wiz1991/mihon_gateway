package moe.radar.mihon_gateway.service

import androidx.preference.EditTextPreference as AndroidEditTextPreference
import androidx.preference.ListPreference as AndroidListPreference
import androidx.preference.MultiSelectListPreference as AndroidMultiSelectListPreference
import androidx.preference.Preference as AndroidPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.CheckBoxPreference as AndroidCheckBoxPreference
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import moe.radar.mihon_gateway.extension.ExtensionManager
import moe.radar.mihon_gateway.proto.*
import moe.radar.mihon_gateway.source.SourceManager
import moe.radar.mihon_gateway.state.StatelessState
import xyz.nulldev.androidcompat.androidimpl.CustomContext

/**
 * Stateless gRPC service implementation for manga sources.
 *
 * Key design principle: Uses URL as primary identifier for manga/chapters.
 * No database - all operations are stateless.
 */
class MangaSourceServiceImpl : MangaSourceServiceGrpcKt.MangaSourceServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    // ==================== Extension Management ====================

    override suspend fun listExtensions(request: ListExtensionsRequest): ListExtensionsResponse {
        logger.debug { "listExtensions called" }

        // Fetch latest from GitHub if cache expired
        ExtensionManager.fetchExtensionsFromGitHub()

        val extensions = ExtensionManager.listExtensions().map { ext ->
            Extension.newBuilder()
                .setPkgName(ext.pkgName)
                .setName(ext.name)
                .setVersionName(ext.versionName)
                .setVersionCode(ext.versionCode)
                .setLang(ext.lang)
                .setIsNsfw(ext.isNsfw)
                .setIsInstalled(ext.isInstalled)
                .setHasUpdate(ext.hasUpdate)
                .setIconUrl(ext.iconUrl)
                .setRepo(ext.repo ?: "")
                .build()
        }

        return ListExtensionsResponse.newBuilder()
            .addAllExtensions(extensions)
            .build()
    }

    override suspend fun installExtension(request: InstallExtensionRequest): InstallExtensionResponse {
        logger.info { "installExtension: ${request.pkgName}" }

        return ExtensionManager.installExtension(request.pkgName)
            .fold(
                onSuccess = { message ->
                    InstallExtensionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage(message)
                        .build()
                },
                onFailure = { error ->
                    logger.error(error) { "Failed to install extension: ${request.pkgName}" }
                    InstallExtensionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage(error.message ?: "Unknown error")
                        .build()
                }
            )
    }

    override suspend fun uninstallExtension(request: UninstallExtensionRequest): Empty {
        logger.info { "uninstallExtension: ${request.pkgName}" }

        ExtensionManager.uninstallExtension(request.pkgName)
            .onFailure { error ->
                logger.error(error) { "Failed to uninstall extension: ${request.pkgName}" }
                throw io.grpc.StatusException(
                    io.grpc.Status.INTERNAL.withDescription(error.message ?: "Unknown error")
                )
            }

        return Empty.getDefaultInstance()
    }

    override suspend fun updateExtension(request: UpdateExtensionRequest): UpdateExtensionResponse {
        logger.info { "updateExtension: ${request.pkgName}" }

        return ExtensionManager.updateExtension(request.pkgName)
            .fold(
                onSuccess = { message ->
                    UpdateExtensionResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage(message)
                        .build()
                },
                onFailure = { error ->
                    logger.error(error) { "Failed to update extension: ${request.pkgName}" }
                    UpdateExtensionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage(error.message ?: "Unknown error")
                        .build()
                }
            )
    }

    // ==================== Source Management ====================

    override suspend fun listSources(request: ListSourcesRequest): ListSourcesResponse {
        logger.debug { "listSources called" }

        val sources = SourceManager.listSources().map { sourceInfo ->
            val iconUrl = sourceInfo.extensionPkgName
                ?.let { StatelessState.extensions[it]?.iconUrl }
                ?: ""

            Source.newBuilder()
                .setId(sourceInfo.id)
                .setName(sourceInfo.name)
                .setLang(sourceInfo.lang)
                .setSupportsLatest(sourceInfo.supportsLatest)
                .setIsConfigurable(sourceInfo.isConfigurable)
                .setIsNsfw(sourceInfo.isNsfw)
                .setDisplayName(sourceInfo.displayName)
                .apply { sourceInfo.baseUrl?.let { setBaseUrl(it) } }
                .setIconUrl(iconUrl)
                .build()
        }

        return ListSourcesResponse.newBuilder()
            .addAllSources(sources)
            .build()
    }

    override suspend fun getSource(request: GetSourceRequest): Source {
        logger.debug { "getSource: ${request.sourceId}" }

        val sourceInfo = SourceManager.getSourceInfo(request.sourceId)
            ?: throw io.grpc.StatusException(
                io.grpc.Status.NOT_FOUND.withDescription("Source ${request.sourceId} not found")
            )

        val iconUrl = sourceInfo.extensionPkgName
            ?.let { StatelessState.extensions[it]?.iconUrl }
            ?: ""

        return Source.newBuilder()
            .setId(sourceInfo.id)
            .setName(sourceInfo.name)
            .setLang(sourceInfo.lang)
            .setSupportsLatest(sourceInfo.supportsLatest)
            .setIsConfigurable(sourceInfo.isConfigurable)
            .setIsNsfw(sourceInfo.isNsfw)
            .setDisplayName(sourceInfo.displayName)
            .apply { sourceInfo.baseUrl?.let { setBaseUrl(it) } }
            .setIconUrl(iconUrl)
            .build()
    }

    // ==================== Source Preferences ====================

    override suspend fun getSourcePreferences(request: GetSourcePreferencesRequest): GetSourcePreferencesResponse {
        logger.debug { "getSourcePreferences: sourceId=${request.sourceId}" }

        val source = SourceManager.getCatalogueSourceOrThrow(request.sourceId)
        if (source !is ConfigurableSource) {
            throw io.grpc.StatusException(
                io.grpc.Status.FAILED_PRECONDITION.withDescription("Source ${request.sourceId} is not configurable")
            )
        }

        val screen = getOrCreatePreferenceScreen(request.sourceId, source)
        return buildPreferencesResponse(screen)
    }

    override suspend fun setSourcePreference(request: SetSourcePreferenceRequest): GetSourcePreferencesResponse {
        logger.debug { "setSourcePreference: sourceId=${request.sourceId}, key=${request.key}" }

        val source = SourceManager.getCatalogueSourceOrThrow(request.sourceId)
        if (source !is ConfigurableSource) {
            throw io.grpc.StatusException(
                io.grpc.Status.FAILED_PRECONDITION.withDescription("Source ${request.sourceId} is not configurable")
            )
        }

        val screen = getOrCreatePreferenceScreen(request.sourceId, source)
        val pref = screen.preferences.find { it.key == request.key }
            ?: throw io.grpc.StatusException(
                io.grpc.Status.NOT_FOUND.withDescription("Preference key '${request.key}' not found")
            )

        // Extract and validate the value
        val newValue: Any = when {
            request.hasBoolValue() -> {
                if (pref.defaultValueType != "Boolean") {
                    throw io.grpc.StatusException(
                        io.grpc.Status.INVALID_ARGUMENT.withDescription(
                            "Expected ${pref.defaultValueType} but got Boolean for key '${request.key}'"
                        )
                    )
                }
                request.boolValue
            }
            request.hasStringValue() -> {
                if (pref.defaultValueType != "String") {
                    throw io.grpc.StatusException(
                        io.grpc.Status.INVALID_ARGUMENT.withDescription(
                            "Expected ${pref.defaultValueType} but got String for key '${request.key}'"
                        )
                    )
                }
                // Validate against allowed values for ListPreference
                if (pref is AndroidListPreference) {
                    val entryValues = pref.entryValues
                    if (entryValues != null && entryValues.none { it.toString() == request.stringValue }) {
                        throw io.grpc.StatusException(
                            io.grpc.Status.INVALID_ARGUMENT.withDescription(
                                "Value '${request.stringValue}' is not a valid entry for key '${request.key}'"
                            )
                        )
                    }
                }
                request.stringValue
            }
            request.hasStringListValue() -> {
                if (pref.defaultValueType != "Set<String>") {
                    throw io.grpc.StatusException(
                        io.grpc.Status.INVALID_ARGUMENT.withDescription(
                            "Expected ${pref.defaultValueType} but got Set<String> for key '${request.key}'"
                        )
                    )
                }
                request.stringListValue.valuesList.toSet()
            }
            else -> throw io.grpc.StatusException(
                io.grpc.Status.INVALID_ARGUMENT.withDescription("No value provided")
            )
        }

        // Call change listener — extension may reject
        if (!pref.callChangeListener(newValue)) {
            throw io.grpc.StatusException(
                io.grpc.Status.FAILED_PRECONDITION.withDescription("Extension rejected value change for key '${request.key}'")
            )
        }

        // Persist to SharedPreferences
        pref.saveNewValue(newValue)

        // Reload source so new constructor picks up updated prefs
        val extensionPkgName = StatelessState.sources[request.sourceId]?.extensionPkgName
            ?: throw io.grpc.StatusException(
                io.grpc.Status.INTERNAL.withDescription("Cannot find extension package for source ${request.sourceId}")
            )
        ExtensionManager.reloadExtensionSources(extensionPkgName)

        // Build fresh preference screen for the reloaded source
        val reloadedSource = SourceManager.getCatalogueSourceOrThrow(request.sourceId)
        if (reloadedSource !is ConfigurableSource) {
            throw io.grpc.StatusException(
                io.grpc.Status.INTERNAL.withDescription("Source ${request.sourceId} is no longer configurable after reload")
            )
        }
        val freshScreen = getOrCreatePreferenceScreen(request.sourceId, reloadedSource)
        return buildPreferencesResponse(freshScreen)
    }

    private fun getOrCreatePreferenceScreen(sourceId: Long, source: ConfigurableSource): PreferenceScreen {
        return StatelessState.preferenceScreenCache.getOrPut(sourceId) {
            val screen = PreferenceScreen(CustomContext())
            screen.sharedPreferences = source.sourcePreferences()
            source.setupPreferenceScreen(screen)
            screen
        }
    }

    private fun buildPreferencesResponse(screen: PreferenceScreen): GetSourcePreferencesResponse {
        val protoPrefs = screen.preferences
            .filter { it.key != null }
            .mapNotNull { convertPreferenceToProto(it) }

        return GetSourcePreferencesResponse.newBuilder()
            .addAllPreferences(protoPrefs)
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertPreferenceToProto(pref: AndroidPreference): SourcePreference? {
        val builder = SourcePreference.newBuilder()
            .setKey(pref.key)
            .setTitle(pref.title?.toString() ?: "")
            .setSummary(pref.summary?.toString() ?: "")
            .setVisible(pref.visible)
            .setDefaultValueType(pref.defaultValueType)

        when (pref) {
            is SwitchPreferenceCompat -> {
                builder.switchPreference = moe.radar.mihon_gateway.proto.SwitchPreference.newBuilder()
                    .setCurrentValue(pref.currentValue as Boolean)
                    .build()
            }
            is AndroidCheckBoxPreference -> {
                builder.checkBoxPreference = moe.radar.mihon_gateway.proto.CheckBoxPreference.newBuilder()
                    .setCurrentValue(pref.currentValue as Boolean)
                    .build()
            }
            is AndroidEditTextPreference -> {
                builder.editTextPreference = moe.radar.mihon_gateway.proto.EditTextPreference.newBuilder()
                    .setCurrentValue(pref.currentValue as? String ?: "")
                    .setDialogTitle(pref.dialogTitle?.toString() ?: "")
                    .setDialogMessage(pref.dialogMessage?.toString() ?: "")
                    .build()
            }
            is AndroidListPreference -> {
                builder.listPreference = moe.radar.mihon_gateway.proto.ListPreference.newBuilder()
                    .setCurrentValue(pref.currentValue as? String ?: "")
                    .addAllEntries(pref.entries?.map { it.toString() } ?: emptyList())
                    .addAllEntryValues(pref.entryValues?.map { it.toString() } ?: emptyList())
                    .build()
            }
            is AndroidMultiSelectListPreference -> {
                val currentSet = pref.currentValue as? Set<String> ?: emptySet()
                builder.multiSelectListPreference = moe.radar.mihon_gateway.proto.MultiSelectListPreference.newBuilder()
                    .addAllCurrentValue(currentSet)
                    .addAllEntries(pref.entries?.map { it.toString() } ?: emptyList())
                    .addAllEntryValues(pref.entryValues?.map { it.toString() } ?: emptyList())
                    .setDialogTitle(pref.dialogTitle?.toString() ?: "")
                    .setDialogMessage(pref.dialogMessage?.toString() ?: "")
                    .build()
            }
            else -> {
                logger.debug { "Skipping unsupported preference type: ${pref.javaClass.simpleName} (key=${pref.key})" }
                return null
            }
        }

        return builder.build()
    }

    // ==================== Manga Operations (Stateless!) ====================

    /**
     * Get manga details using URL as identifier.
     * Creates an empty SManga instance with just the URL set.
     */
    override suspend fun getMangaDetails(request: GetMangaDetailsRequest): Manga {
        logger.debug { "getMangaDetails: sourceId=${request.sourceId}, url=${request.mangaUrl}" }

        val source = SourceManager.getCatalogueSourceOrThrow(request.sourceId)

        // STATELESS: Create empty SManga with just URL!
        val sManga = SManga.create().apply {
            url = request.mangaUrl
        }

        // Fetch details from source
        val details = source.getMangaDetails(sManga)

        // Convert to protobuf (no DB storage!)
        return details.toProto(request.sourceId)
    }

    /**
     * Search manga - returns all results at once.
     */
    override suspend fun searchManga(request: SearchMangaRequest): SearchMangaResponse {
        logger.debug { "searchManga: sourceId=${request.sourceId}, query=${request.query}, page=${request.page}" }

        val source = SourceManager.getCatalogueSourceOrThrow(request.sourceId)

        // Fetch search results
        val results = source.getSearchManga(
            page = request.page,
            query = request.query,
            filters = FilterList()
        )

        // Convert all manga to protobuf
        val mangaList = results.mangas.map { sManga ->
            sManga.toProto(request.sourceId)
        }

        return SearchMangaResponse.newBuilder()
            .addAllManga(mangaList)
            .setHasNextPage(results.hasNextPage)
            .build()
    }

    /**
     * Get popular manga - returns all results at once.
     */
    override suspend fun getPopularManga(request: GetPopularMangaRequest): GetPopularMangaResponse {
        logger.debug { "getPopularManga: sourceId=${request.sourceId}, page=${request.page}" }

        val source = SourceManager.getCatalogueSourceOrThrow(request.sourceId)

        val results = source.getPopularManga(request.page)

        // Convert all manga to protobuf
        val mangaList = results.mangas.map { sManga ->
            sManga.toProto(request.sourceId)
        }

        return GetPopularMangaResponse.newBuilder()
            .addAllManga(mangaList)
            .setHasNextPage(results.hasNextPage)
            .build()
    }

    /**
     * Get latest manga - returns all results at once.
     */
    override suspend fun getLatestManga(request: GetLatestMangaRequest): GetLatestMangaResponse {
        logger.debug { "getLatestManga: sourceId=${request.sourceId}, page=${request.page}" }

        val source = SourceManager.getCatalogueSourceOrThrow(request.sourceId)

        if (!source.supportsLatest) {
            throw io.grpc.StatusException(
                io.grpc.Status.UNIMPLEMENTED.withDescription("Source does not support latest manga")
            )
        }

        val results = source.getLatestUpdates(request.page)

        // Convert all manga to protobuf
        val mangaList = results.mangas.map { sManga ->
            sManga.toProto(request.sourceId)
        }

        return GetLatestMangaResponse.newBuilder()
            .addAllManga(mangaList)
            .setHasNextPage(results.hasNextPage)
            .build()
    }

    // ==================== Chapter Operations ====================

    /**
     * Get chapter list for a manga (using URL).
     */
    override suspend fun getChapterList(request: GetChapterListRequest): ChapterListResponse {
        logger.debug { "getChapterList: sourceId=${request.sourceId}, mangaUrl=${request.mangaUrl}" }

        val source = SourceManager.getCatalogueSourceOrThrow(request.sourceId)

        // STATELESS: Create empty SManga with just URL
        val sManga = SManga.create().apply {
            url = request.mangaUrl
        }

        val chapters = source.getChapterList(sManga)

        return ChapterListResponse.newBuilder()
            .addAllChapters(chapters.map { it.toProto() })
            .build()
    }

    /**
     * Get page list for a chapter (using URL).
     */
    override suspend fun getPageList(request: GetPageListRequest): PageListResponse {
        logger.debug { "getPageList: sourceId=${request.sourceId}, chapterUrl=${request.chapterUrl}" }

        val source = SourceManager.getCatalogueSourceOrThrow(request.sourceId)

        // STATELESS: Create empty SChapter with just URL
        val sChapter = SChapter.create().apply {
            url = request.chapterUrl
        }

        val pages = source.getPageList(sChapter)

        return PageListResponse.newBuilder()
            .addAllPages(pages.map { page ->
                moe.radar.mihon_gateway.proto.Page.newBuilder()
                    .setIndex(page.index)
                    .setImageUrl(page.imageUrl ?: page.url)
                    .build()
            })
            .build()
    }

    // ==================== Helper Extensions ====================

    /**
     * Convert SManga to protobuf Manga message.
     */
    private fun SManga.toProto(sourceId: Long): Manga {
        val source = SourceManager.getCatalogueSourceOrThrow(sourceId)
        val thumbnailUrl = this.thumbnail_url?.let { rewriteLocalhostUrl(it, source) }

        return Manga.newBuilder()
            .setUrl(this.url)
            .setSourceId(sourceId)
            .setTitle(this.title)
            .apply { this@toProto.author?.let { setAuthor(it) } }
            .apply { this@toProto.artist?.let { setArtist(it) } }
            .apply { this@toProto.description?.let { setDescription(it) } }
            .addAllGenre(this.genre?.split(", ") ?: emptyList())
            .setStatus(this.status.toStatusString())
            .apply { thumbnailUrl?.let { setThumbnailUrl(it) } }
            .setInitialized(this.initialized)
            .build()
    }

    /**
     * Resolve thumbnail URL to be externally accessible.
     * Some extensions (like MangaPark) use 127.0.0.1 as a placeholder that gets
     * rewritten by an interceptor at request time. For external access, we need
     * to return the actual resolvable URL.
     *
     * This simulates what the source's thumbnail interceptor would do by:
     * 1. Detecting localhost/127.0.0.1 URLs
     * 2. Replacing with the source's actual base domain
     * 3. Handling relative URLs by prepending base URL
     */
    private fun rewriteLocalhostUrl(url: String, source: eu.kanade.tachiyomi.source.CatalogueSource): String {
        if (source !is HttpSource) return url

        val baseUrl = source.baseUrl

        // Handle relative URLs (start with /)
        if (url.startsWith("/")) {
            return "$baseUrl$url"
        }

        // Handle localhost or 127.0.0.1 URLs
        if (url.contains("://localhost/") || url.contains("://127.0.0.1/")) {
            try {
                val uri = java.net.URI(url)
                val path = uri.path
                val query = if (uri.query != null) "?${uri.query}" else ""
                val fragment = if (uri.fragment != null) "#${uri.fragment}" else ""

                // Reconstruct URL with actual base URL
                return "$baseUrl$path$query$fragment"
            } catch (e: Exception) {
                logger.warn { "Failed to parse URL $url: ${e.message}" }

                // Fallback: simple string replacement
                return url
                    .replace("://localhost/", "://${java.net.URI(baseUrl).host}/")
                    .replace("://127.0.0.1/", "://${java.net.URI(baseUrl).host}/")
            }
        }

        return url
    }

    /**
     * Convert SChapter to protobuf Chapter message.
     */
    private fun SChapter.toProto(): Chapter {
        return Chapter.newBuilder()
            .setUrl(this.url)
            .setName(this.name)
            .setDateUpload(this.date_upload)
            .setChapterNumber(this.chapter_number)
            .apply { this@toProto.scanlator?.let { setScanlator(it) } }
            .build()
    }

    /**
     * Convert SManga status int to string.
     */
    private fun Int.toStatusString(): String {
        return when (this) {
            SManga.UNKNOWN -> "UNKNOWN"
            SManga.ONGOING -> "ONGOING"
            SManga.COMPLETED -> "COMPLETED"
            SManga.LICENSED -> "LICENSED"
            SManga.PUBLISHING_FINISHED -> "PUBLISHING_FINISHED"
            SManga.CANCELLED -> "CANCELLED"
            SManga.ON_HIATUS -> "ON_HIATUS"
            else -> "UNKNOWN"
        }
    }
}
