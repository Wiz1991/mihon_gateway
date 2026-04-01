package moe.radar.mihon_gateway.service

import androidx.preference.EditTextPreference as AndroidEditTextPreference
import androidx.preference.ListPreference as AndroidListPreference
import androidx.preference.MultiSelectListPreference as AndroidMultiSelectListPreference
import androidx.preference.Preference as AndroidPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.CheckBoxPreference as AndroidCheckBoxPreference
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.LicensedMangaChaptersException
import eu.kanade.tachiyomi.util.chapter.ChapterRecognition
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Status
import io.opentelemetry.context.Context
import moe.radar.mihon_gateway.extension.ExtensionManager
import moe.radar.mihon_gateway.proto.*
import moe.radar.mihon_gateway.source.SourceManager
import moe.radar.mihon_gateway.state.StatelessState
import moe.radar.mihon_gateway.telemetry.GrpcTracingInterceptor
import moe.radar.mihon_gateway.telemetry.Telemetry
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import xyz.nulldev.androidcompat.webkit.LocalStorageManager
import java.net.URI

/**
 * Stateless gRPC service implementation for manga sources.
 *
 * Key design principle: Uses URL as primary identifier for manga/chapters.
 * No database - all operations are stateless.
 */
class MangaSourceServiceImpl : MangaSourceServiceGrpcKt.MangaSourceServiceCoroutineImplBase(), KoinComponent {
    private val logger = KotlinLogging.logger {}
    private val networkHelper: NetworkHelper by inject()

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
                throw grpcError(
                    Status.NOT_FOUND,
                    ErrorCode.EXTENSION_NOT_INSTALLED,
                    "Extension ${request.pkgName} is not installed"
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

    // ==================== Extension Repo Management ====================

    override suspend fun addExtensionRepo(request: AddExtensionRepoRequest): AddExtensionRepoResponse {
        logger.info { "addExtensionRepo: ${request.repoUrl}" }

        val repos = ExtensionManager.addRepo(request.repoUrl)

        return AddExtensionRepoResponse.newBuilder()
            .addAllRepos(repos)
            .build()
    }

    override suspend fun removeExtensionRepo(request: RemoveExtensionRepoRequest): Empty {
        logger.info { "removeExtensionRepo: ${request.repoUrl}" }

        ExtensionManager.removeRepo(request.repoUrl)

        return Empty.getDefaultInstance()
    }

    override suspend fun listExtensionRepos(request: ListExtensionReposRequest): ListExtensionReposResponse {
        logger.debug { "listExtensionRepos called" }

        return ListExtensionReposResponse.newBuilder()
            .addAllRepos(ExtensionManager.listRepos())
            .build()
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
            ?: throw grpcError(
                Status.NOT_FOUND,
                ErrorCode.SOURCE_NOT_FOUND,
                "Source ${request.sourceId} not found"
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

        val source = getSourceOrThrow(request.sourceId)
        if (source !is ConfigurableSource) {
            throw grpcError(
                Status.FAILED_PRECONDITION,
                ErrorCode.SOURCE_NOT_CONFIGURABLE,
                "Source ${request.sourceId} is not configurable"
            )
        }

        val screen = getOrCreatePreferenceScreen(request.sourceId, source)
        return buildPreferencesResponse(screen)
    }

    override suspend fun setSourcePreference(request: SetSourcePreferenceRequest): GetSourcePreferencesResponse {
        logger.debug { "setSourcePreference: sourceId=${request.sourceId}, key=${request.key}" }

        val source = getSourceOrThrow(request.sourceId)
        if (source !is ConfigurableSource) {
            throw grpcError(
                Status.FAILED_PRECONDITION,
                ErrorCode.SOURCE_NOT_CONFIGURABLE,
                "Source ${request.sourceId} is not configurable"
            )
        }

        val screen = getOrCreatePreferenceScreen(request.sourceId, source)
        val pref = screen.preferences.find { it.key == request.key }
            ?: throw grpcError(
                Status.NOT_FOUND,
                ErrorCode.PREFERENCE_NOT_FOUND,
                "Preference key '${request.key}' not found"
            )

        // Extract and validate the value
        val newValue: Any = when {
            request.hasBoolValue() -> {
                if (pref.defaultValueType != "Boolean") {
                    throw grpcError(
                        Status.INVALID_ARGUMENT,
                        ErrorCode.PREFERENCE_TYPE_MISMATCH,
                        "Expected ${pref.defaultValueType} but got Boolean for key '${request.key}'"
                    )
                }
                request.boolValue
            }
            request.hasStringValue() -> {
                if (pref.defaultValueType != "String") {
                    throw grpcError(
                        Status.INVALID_ARGUMENT,
                        ErrorCode.PREFERENCE_TYPE_MISMATCH,
                        "Expected ${pref.defaultValueType} but got String for key '${request.key}'"
                    )
                }
                // Validate against allowed values for ListPreference
                if (pref is AndroidListPreference) {
                    val entryValues = pref.entryValues
                    if (entryValues != null && entryValues.none { it.toString() == request.stringValue }) {
                        throw grpcError(
                            Status.INVALID_ARGUMENT,
                            ErrorCode.PREFERENCE_VALUE_INVALID,
                            "Value '${request.stringValue}' is not a valid entry for key '${request.key}'"
                        )
                    }
                }
                request.stringValue
            }
            request.hasStringListValue() -> {
                if (pref.defaultValueType != "Set<String>") {
                    throw grpcError(
                        Status.INVALID_ARGUMENT,
                        ErrorCode.PREFERENCE_TYPE_MISMATCH,
                        "Expected ${pref.defaultValueType} but got Set<String> for key '${request.key}'"
                    )
                }
                request.stringListValue.valuesList.toSet()
            }
            else -> throw grpcError(
                Status.INVALID_ARGUMENT,
                ErrorCode.PREFERENCE_NO_VALUE,
                "No value provided in request"
            )
        }

        // Call change listener — extension may reject
        if (!pref.callChangeListener(newValue)) {
            throw grpcError(
                Status.FAILED_PRECONDITION,
                ErrorCode.PREFERENCE_VALUE_REJECTED,
                "Extension rejected value change for key '${request.key}'"
            )
        }

        // Persist to SharedPreferences
        pref.saveNewValue(newValue)

        // Reload source so new constructor picks up updated prefs
        val extensionPkgName = StatelessState.sources[request.sourceId]?.extensionPkgName
            ?: throw grpcError(
                Status.INTERNAL,
                ErrorCode.INTERNAL_ERROR,
                "Cannot find extension package for source ${request.sourceId}"
            )
        ExtensionManager.reloadExtensionSources(extensionPkgName)

        // Build fresh preference screen for the reloaded source
        val reloadedSource = getSourceOrThrow(request.sourceId)
        if (reloadedSource !is ConfigurableSource) {
            throw grpcError(
                Status.INTERNAL,
                ErrorCode.INTERNAL_ERROR,
                "Source ${request.sourceId} is no longer configurable after reload"
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

    // ==================== Cookie Management (Layer 1) ====================

    override suspend fun setCookies(request: SetCookiesRequest): SetCookiesResponse {
        logger.debug { "setCookies: url=${request.url}, count=${request.cookiesList.size}" }

        val httpUrl = request.url.toHttpUrlOrNull()
            ?: throw grpcError(
                Status.INVALID_ARGUMENT,
                ErrorCode.INVALID_URL,
                "Invalid URL: ${request.url}"
            )

        val cookies = request.cookiesList.map { cookieData ->
            Cookie.Builder()
                .name(cookieData.name)
                .value(cookieData.value)
                .domain(cookieData.domain.ifEmpty { httpUrl.host })
                .path(cookieData.path.ifEmpty { "/" })
                .apply {
                    if (cookieData.secure) secure()
                    if (cookieData.httpOnly) httpOnly()
                    if (cookieData.expiresAt > 0) expiresAt(cookieData.expiresAt)
                    else expiresAt(Long.MAX_VALUE)
                }
                .build()
        }

        networkHelper.cookieStore.addAll(httpUrl, cookies)

        return SetCookiesResponse.newBuilder()
            .setCookiesSet(cookies.size)
            .build()
    }

    override suspend fun getCookies(request: GetCookiesRequest): GetCookiesResponse {
        logger.debug { "getCookies: url=${request.url}" }

        val httpUrl = request.url.toHttpUrlOrNull()
            ?: throw grpcError(
                Status.INVALID_ARGUMENT,
                ErrorCode.INVALID_URL,
                "Invalid URL: ${request.url}"
            )

        val cookies = networkHelper.cookieStore.get(httpUrl)

        return GetCookiesResponse.newBuilder()
            .addAllCookies(cookies.map { cookie ->
                CookieData.newBuilder()
                    .setName(cookie.name)
                    .setValue(cookie.value)
                    .setDomain(cookie.domain)
                    .setPath(cookie.path)
                    .setSecure(cookie.secure)
                    .setHttpOnly(cookie.httpOnly)
                    .setExpiresAt(cookie.expiresAt)
                    .build()
            })
            .build()
    }

    override suspend fun clearCookies(request: ClearCookiesRequest): Empty {
        logger.debug { "clearCookies: url=${request.url}" }

        if (request.url.isEmpty()) {
            networkHelper.cookieStore.removeAll()
        } else {
            val uri = try {
                URI(request.url)
            } catch (e: Exception) {
                throw grpcError(
                    Status.INVALID_ARGUMENT,
                    ErrorCode.INVALID_URL,
                    "Invalid URL: ${request.url}"
                )
            }
            networkHelper.cookieStore.remove(uri)
        }

        return Empty.getDefaultInstance()
    }

    // ==================== LocalStorage Management (Layer 1) ====================

    override suspend fun setLocalStorageItem(request: SetLocalStorageItemRequest): Empty {
        logger.debug { "setLocalStorageItem: url=${request.url}, key=${request.key}" }

        val origin = LocalStorageManager.extractOrigin(request.url)
        LocalStorageManager.setItem(origin, request.key, request.value)

        return Empty.getDefaultInstance()
    }

    override suspend fun getLocalStorageItems(request: GetLocalStorageItemsRequest): GetLocalStorageItemsResponse {
        logger.debug { "getLocalStorageItems: url=${request.url}" }

        val origin = LocalStorageManager.extractOrigin(request.url)
        val items = LocalStorageManager.getAllItems(origin)

        return GetLocalStorageItemsResponse.newBuilder()
            .putAllItems(items)
            .build()
    }

    override suspend fun clearLocalStorage(request: ClearLocalStorageRequest): Empty {
        logger.debug { "clearLocalStorage: url=${request.url}" }

        val origin = LocalStorageManager.extractOrigin(request.url)
        LocalStorageManager.clear(origin)

        return Empty.getDefaultInstance()
    }

    // ==================== Manga Operations (Stateless!) ====================

    /**
     * Get manga details using URL as identifier.
     * Creates an empty SManga instance with just the URL set.
     */
    override suspend fun getMangaDetails(request: GetMangaDetailsRequest): Manga {
        logger.info { "getMangaDetails sourceId=${request.sourceId} url=${request.mangaUrl}" }

        val source = getSourceOrThrow(request.sourceId)

        return withSourceSpan("getMangaDetails", request.sourceId, source.name, requestUrl = request.mangaUrl) { span ->
            span.setAttribute("request.manga_url", request.mangaUrl)

            val sManga = SManga.create().apply {
                url = request.mangaUrl
            }

            val details = try {
                source.getMangaDetails(sManga)
            } catch (e: HttpException) {
                throw grpcHttpError(e.code, "Failed to fetch manga details from source")
            } catch (e: Exception) {
                logger.error(e) { "getMangaDetails failed sourceId=${request.sourceId} url=${request.mangaUrl}" }
                throw grpcError(
                    Status.INTERNAL,
                    ErrorCode.FETCH_FAILED,
                    "Failed to fetch manga details: ${e.message}"
                )
            }

            details.url = request.mangaUrl
            details.toProto(request.sourceId)
        }
    }

    /**
     * Search manga - returns all results at once.
     */
    override suspend fun searchManga(request: SearchMangaRequest): SearchMangaResponse {
        logger.info { "searchManga sourceId=${request.sourceId} query=${request.query} page=${request.page}" }

        val source = getSourceOrThrow(request.sourceId)

        return withSourceSpan("searchManga", request.sourceId, source.name) { span ->
            span.setAttribute("request.query", request.query)
            span.setAttribute("request.page", request.page.toLong())

            val results = try {
                source.getSearchManga(
                    page = request.page,
                    query = request.query,
                    filters = FilterList()
                )
            } catch (e: HttpException) {
                if (e.code == 404) {
                    logger.info { "searchManga returned 404, treating as empty results" }
                    return@withSourceSpan SearchMangaResponse.newBuilder()
                        .setHasNextPage(false)
                        .build()
                }
                throw grpcHttpError(e.code, "Search failed")
            } catch (e: Exception) {
                logger.error(e) { "searchManga failed sourceId=${request.sourceId}" }
                throw grpcError(
                    Status.INTERNAL,
                    ErrorCode.FETCH_FAILED,
                    "Search failed: ${e.message}"
                )
            }

            span.setAttribute("results.count", results.mangas.size.toLong())

            val mangaList = results.mangas.map { sManga ->
                sManga.toProto(request.sourceId)
            }

            SearchMangaResponse.newBuilder()
                .addAllManga(mangaList)
                .setHasNextPage(results.hasNextPage)
                .build()
        }
    }

    /**
     * Get popular manga - returns all results at once.
     */
    override suspend fun getPopularManga(request: GetPopularMangaRequest): GetPopularMangaResponse {
        logger.info { "getPopularManga sourceId=${request.sourceId} page=${request.page}" }

        val source = getSourceOrThrow(request.sourceId)

        return withSourceSpan("getPopularManga", request.sourceId, source.name) { span ->
            span.setAttribute("request.page", request.page.toLong())

            val results = try {
                source.getPopularManga(request.page)
            } catch (e: HttpException) {
                throw grpcHttpError(e.code, "Failed to fetch popular manga")
            } catch (e: Exception) {
                logger.error(e) { "getPopularManga failed sourceId=${request.sourceId}" }
                throw grpcError(
                    Status.INTERNAL,
                    ErrorCode.FETCH_FAILED,
                    "Failed to fetch popular manga: ${e.message}"
                )
            }

            span.setAttribute("results.count", results.mangas.size.toLong())

            val mangaList = results.mangas.map { sManga ->
                sManga.toProto(request.sourceId)
            }

            GetPopularMangaResponse.newBuilder()
                .addAllManga(mangaList)
                .setHasNextPage(results.hasNextPage)
                .build()
        }
    }

    /**
     * Get latest manga - returns all results at once.
     */
    override suspend fun getLatestManga(request: GetLatestMangaRequest): GetLatestMangaResponse {
        logger.info { "getLatestManga sourceId=${request.sourceId} page=${request.page}" }

        val source = getSourceOrThrow(request.sourceId)

        if (!source.supportsLatest) {
            throw grpcError(
                Status.UNIMPLEMENTED,
                ErrorCode.SOURCE_LATEST_NOT_SUPPORTED,
                "Source ${request.sourceId} does not support latest manga"
            )
        }

        return withSourceSpan("getLatestManga", request.sourceId, source.name) { span ->
            span.setAttribute("request.page", request.page.toLong())

            val results = try {
                source.getLatestUpdates(request.page)
            } catch (e: HttpException) {
                throw grpcHttpError(e.code, "Failed to fetch latest manga")
            } catch (e: Exception) {
                logger.error(e) { "getLatestManga failed sourceId=${request.sourceId}" }
                throw grpcError(
                    Status.INTERNAL,
                    ErrorCode.FETCH_FAILED,
                    "Failed to fetch latest manga: ${e.message}"
                )
            }

            span.setAttribute("results.count", results.mangas.size.toLong())

            val mangaList = results.mangas.map { sManga ->
                sManga.toProto(request.sourceId)
            }

            GetLatestMangaResponse.newBuilder()
                .addAllManga(mangaList)
                .setHasNextPage(results.hasNextPage)
                .build()
        }
    }

    // ==================== Chapter Operations ====================

    /**
     * Get chapter list for a manga (using URL).
     */
    override suspend fun getChapterList(request: GetChapterListRequest): ChapterListResponse {
        logger.info { "getChapterList sourceId=${request.sourceId} mangaUrl=${request.mangaUrl}" }

        val source = getSourceOrThrow(request.sourceId)

        return withSourceSpan("getChapterList", request.sourceId, source.name, requestUrl = request.mangaUrl) { span ->
            span.setAttribute("request.manga_url", request.mangaUrl)

            val sManga = SManga.create().apply {
                url = request.mangaUrl
            }

            val chapters = try {
                source.getChapterList(sManga)
            } catch (e: LicensedMangaChaptersException) {
                throw grpcError(
                    Status.FAILED_PRECONDITION,
                    ErrorCode.MANGA_LICENSED,
                    "Manga is licensed — no chapters available"
                )
            } catch (e: HttpException) {
                throw grpcHttpError(e.code, "Failed to fetch chapter list")
            } catch (e: Exception) {
                logger.error(e) { "getChapterList failed sourceId=${request.sourceId} mangaUrl=${request.mangaUrl}" }
                throw grpcError(
                    Status.INTERNAL,
                    ErrorCode.FETCH_FAILED,
                    "Failed to fetch chapter list: ${e.message}"
                )
            }

            val mangaTitle = request.mangaTitle
            if (mangaTitle.isNotEmpty()) {
                chapters.forEach { chapter ->
                    chapter.chapter_number = ChapterRecognition.parseChapterNumber(
                        mangaTitle,
                        chapter.name,
                        chapter.chapter_number.toDouble(),
                    ).toFloat()
                }
            }

            span.setAttribute("results.count", chapters.size.toLong())

            ChapterListResponse.newBuilder()
                .addAllChapters(chapters.map { it.toProto() })
                .build()
        }
    }

    /**
     * Get page list for a chapter (using URL).
     */
    override suspend fun getPageList(request: GetPageListRequest): PageListResponse {
        logger.info { "getPageList sourceId=${request.sourceId} chapterUrl=${request.chapterUrl}" }

        val source = getSourceOrThrow(request.sourceId)

        return withSourceSpan("getPageList", request.sourceId, source.name, requestUrl = request.chapterUrl) { span ->
            span.setAttribute("request.chapter_url", request.chapterUrl)

            val sChapter = SChapter.create().apply {
                url = request.chapterUrl
            }

            val pages = try {
                source.getPageList(sChapter)
            } catch (e: HttpException) {
                throw grpcHttpError(e.code, "Failed to fetch page list")
            } catch (e: Exception) {
                logger.error(e) { "getPageList failed sourceId=${request.sourceId} chapterUrl=${request.chapterUrl}" }
                throw grpcError(
                    Status.INTERNAL,
                    ErrorCode.FETCH_FAILED,
                    "Failed to fetch page list: ${e.message}"
                )
            }

            span.setAttribute("results.count", pages.size.toLong())

            PageListResponse.newBuilder()
                .addAllPages(pages.map { page ->
                    moe.radar.mihon_gateway.proto.Page.newBuilder()
                        .setIndex(page.index)
                        .setImageUrl(page.imageUrl ?: page.url)
                        .build()
                })
                .build()
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Get a catalogue source by ID or throw a proper gRPC NOT_FOUND error.
     */
    private fun getSourceOrThrow(sourceId: Long) =
        SourceManager.getCatalogueSource(sourceId)
            ?: throw grpcError(
                Status.NOT_FOUND,
                ErrorCode.SOURCE_NOT_FOUND,
                "Source $sourceId not found. Is the extension installed?"
            )

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
     * Run [block] inside an OTLP span scoped to a source operation.
     * Picks up the parent context from the gRPC server span (set by GrpcTracingInterceptor).
     */
    private inline fun <T> withSourceSpan(
        operation: String,
        sourceId: Long,
        sourceName: String,
        requestUrl: String = "",
        block: (io.opentelemetry.api.trace.Span) -> T,
    ): T {
        val parentCtx = GrpcTracingInterceptor.OTEL_CONTEXT_KEY.get()
            ?: Context.current()

        // Resolve the source's base URL for the span
        val sourceBaseUrl = try {
            (SourceManager.getCatalogueSource(sourceId) as? HttpSource)?.baseUrl ?: ""
        } catch (_: Exception) { "" }

        // Build full target URL from base + request path
        val targetUrl = if (requestUrl.isNotEmpty() && sourceBaseUrl.isNotEmpty()) {
            if (requestUrl.startsWith("http")) requestUrl else "$sourceBaseUrl$requestUrl"
        } else ""

        val span = Telemetry.tracer.spanBuilder("source.$operation")
            .setParent(parentCtx)
            .setAttribute("source.id", sourceId)
            .setAttribute("source.name", sourceName)
            .setAttribute("source.base_url", sourceBaseUrl)
            .apply { if (targetUrl.isNotEmpty()) setAttribute("source.target_url", targetUrl) }
            .startSpan()

        return span.makeCurrent().use { _ ->
            org.slf4j.MDC.put("sourceId", sourceId.toString())
            org.slf4j.MDC.put("sourceName", sourceName)
            Telemetry.setMdcFromCurrentSpan()
            try {
                block(span)
            } catch (e: Throwable) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.message ?: "error")
                span.recordException(e)
                throw e
            } finally {
                span.end()
                Telemetry.clearMdc()
            }
        }
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
