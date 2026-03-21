package moe.radar.mihon_gateway.service

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import moe.radar.mihon_gateway.extension.ExtensionManager
import moe.radar.mihon_gateway.proto.*
import moe.radar.mihon_gateway.source.SourceManager

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
            Source.newBuilder()
                .setId(sourceInfo.id)
                .setName(sourceInfo.name)
                .setLang(sourceInfo.lang)
                .setSupportsLatest(sourceInfo.supportsLatest)
                .setIsConfigurable(sourceInfo.isConfigurable)
                .setIsNsfw(sourceInfo.isNsfw)
                .setDisplayName(sourceInfo.displayName)
                .apply { sourceInfo.baseUrl?.let { setBaseUrl(it) } }
                .setIconUrl("") // TODO: Add icon URL support
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

        return Source.newBuilder()
            .setId(sourceInfo.id)
            .setName(sourceInfo.name)
            .setLang(sourceInfo.lang)
            .setSupportsLatest(sourceInfo.supportsLatest)
            .setIsConfigurable(sourceInfo.isConfigurable)
            .setIsNsfw(sourceInfo.isNsfw)
            .setDisplayName(sourceInfo.displayName)
            .apply { sourceInfo.baseUrl?.let { setBaseUrl(it) } }
            .setIconUrl("")
            .build()
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
