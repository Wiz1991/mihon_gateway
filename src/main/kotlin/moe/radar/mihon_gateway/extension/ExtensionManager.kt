package moe.radar.mihon_gateway.extension

import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.radar.mihon_gateway.ApplicationDirs
import moe.radar.mihon_gateway.state.ExtensionMetadata
import moe.radar.mihon_gateway.state.SourceMetadata
import moe.radar.mihon_gateway.state.StatelessState
import suwayomi.tachidesk.manga.impl.extension.github.OnlineExtension
import okhttp3.CacheControl
import okio.buffer
import okio.sink
import okio.source
import suwayomi.tachidesk.manga.impl.extension.github.ExtensionGithubApi
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.PackageTools.EXTENSION_FEATURE
import suwayomi.tachidesk.manga.impl.util.PackageTools.LIB_VERSION_MAX
import suwayomi.tachidesk.manga.impl.util.PackageTools.LIB_VERSION_MIN
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_NSFW
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_SOURCE_CLASS
import suwayomi.tachidesk.manga.impl.util.PackageTools.dex2jar
import suwayomi.tachidesk.manga.impl.util.PackageTools.getPackageInfo
import suwayomi.tachidesk.manga.impl.util.PackageTools.loadExtensionSources
import suwayomi.tachidesk.manga.impl.util.network.await
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.outputStream

/**
 * Stateless extension manager - manages extensions in memory without database.
 */
object ExtensionManager {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs = ApplicationDirs.instance
    private val networkHelper = NetworkHelper(CustomContext())
    private val reloadLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Fetch extension list from GitHub repositories
     */
    suspend fun fetchExtensionsFromGitHub(
        repos: List<String> = listOf("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json")
    ) {
        if (StatelessState.isExtensionListCacheValid()) {
            logger.debug { "Using cached extension list" }
            return
        }

        logger.info { "Fetching extension list from GitHub..." }

        val extensions = repos.flatMap { repo ->
            runCatching {
                ExtensionGithubApi.findExtensions(repo)
            }.onFailure {
                logger.warn(it) { "Failed to fetch extensions from $repo" }
            }.getOrNull() ?: emptyList()
        }

        StatelessState.updateExtensionListCache(extensions)
        updateInMemoryExtensionList(extensions)

        logger.info { "Fetched ${extensions.size} extensions from GitHub" }
    }

    /**
     * Update in-memory extension list with fetched data
     */
    private fun updateInMemoryExtensionList(onlineExtensions: List<OnlineExtension>) {
        onlineExtensions.forEach { ext ->
            val existing = StatelessState.extensions[ext.pkgName]
            val isInstalled = existing?.isInstalled ?: false
            val hasUpdate = if (isInstalled && existing != null) {
                ext.versionCode > existing.versionCode
            } else {
                false
            }

            StatelessState.extensions[ext.pkgName] = ExtensionMetadata(
                pkgName = ext.pkgName,
                name = ext.name,
                versionName = ext.versionName,
                versionCode = ext.versionCode,
                lang = ext.lang,
                isNsfw = ext.isNsfw,
                isInstalled = isInstalled,
                hasUpdate = hasUpdate,
                iconUrl = ext.iconUrl,
                repo = ext.repo ?: "",
                apkName = ext.apkName,
                classFQName = existing?.classFQName ?: "" // Will be populated on install
            )
        }
    }

    /**
     * Install an extension by package name
     */
    suspend fun installExtension(pkgName: String): Result<String> = withContext(Dispatchers.IO) {
        logger.info { "Installing extension: $pkgName" }

        runCatching {
            val extensionMeta = StatelessState.extensions[pkgName]
                ?: throw IllegalArgumentException("Extension $pkgName not found in available extensions")

            val repo = extensionMeta.repo
                ?: throw IllegalArgumentException("Extension $pkgName has no repository URL")

            // Download APK
            val apkURL = ExtensionGithubApi.getApkUrl(repo, extensionMeta.apkName)
            val apkName = Uri.parse(apkURL).lastPathSegment!!
            val apkPath = "${applicationDirs.extensionsRoot}/$apkName"

            downloadAPKFile(apkURL, apkPath)

            // Convert to JAR and load
            installAPK(apkPath, pkgName)

            "Extension $pkgName installed successfully"
        }
    }

    /**
     * Download APK file from URL
     */
    private suspend fun downloadAPKFile(url: String, savePath: String) {
        logger.debug { "Downloading APK from $url" }

        val request = GET(url, cache = CacheControl.FORCE_NETWORK)
        val response = networkHelper.client.newCall(request).await()

        if (!response.isSuccessful) {
            throw Exception("Failed to download APK: ${response.code}")
        }

        File(savePath).outputStream().sink().buffer().use { sink ->
            response.body.source().use { source ->
                sink.writeAll(source)
                sink.flush()
            }
        }

        logger.debug { "APK downloaded to $savePath" }
    }

    /**
     * Install APK: convert to JAR and load sources
     */
    private fun installAPK(apkPath: String, pkgName: String): String {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            throw IllegalArgumentException("APK file not found: $apkPath")
        }

        // Get package info
        val packageInfo = getPackageInfo(apkPath)
        val pkgNameFromApk = packageInfo.packageName

        if (pkgNameFromApk != pkgName) {
            logger.warn { "Package name mismatch: expected $pkgName, got $pkgNameFromApk" }
        }

        // Extract metadata
        val appInfo = packageInfo.applicationInfo
        val extName = packageInfo.applicationInfo.nonLocalizedLabel.toString()
        val versionName = packageInfo.versionName
        val versionCode = packageInfo.versionCode

        val hasExtensionFeature = packageInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
        if (!hasExtensionFeature) {
            throw IllegalArgumentException("APK is not a Tachiyomi extension")
        }

        val classNameFromMeta = appInfo.metaData.getString(METADATA_SOURCE_CLASS)!!
        // Metadata values are stored as strings in the Bundle
        val nsfw = appInfo.metaData.getString(METADATA_NSFW)?.toIntOrNull() ?: 0

        // Resolve class name - handle relative paths from Android manifest
        // Class names can be: ".ClassName", "/ClassName", or "full.package.ClassName"
        val className = when {
            classNameFromMeta.startsWith(".") -> pkgNameFromApk + classNameFromMeta
            classNameFromMeta.startsWith("/") -> pkgNameFromApk + "." + classNameFromMeta.substring(1)
            else -> classNameFromMeta
        }

        logger.debug { "Resolved class name: $className (from metadata: $classNameFromMeta)" }

        // Validate lib version - extract from versionName (e.g., "1.4.0" -> 1.4)
        val libVersion = versionName.substringBeforeLast('.').toDoubleOrNull() ?: 0.0
        if (libVersion !in LIB_VERSION_MIN..LIB_VERSION_MAX) {
            throw IllegalArgumentException(
                "Extension lib version $libVersion is not supported (min: $LIB_VERSION_MIN, max: $LIB_VERSION_MAX)"
            )
        }

        // Convert APK to JAR
        val jarName = apkFile.nameWithoutExtension + ".jar"
        val jarPath = "${applicationDirs.extensionsRoot}/$jarName"

        logger.debug { "Converting APK to JAR: $jarPath" }
        dex2jar(apkPath, jarPath, applicationDirs.extensionsRoot)

        // Extract assets from APK and merge into JAR
        logger.debug { "Extracting assets from APK" }
        extractAssetsFromApk(apkPath, jarPath)

        // Load sources from JAR
        loadSourcesFromJar(jarPath, className, pkgNameFromApk)

        // Update in-memory state
        val existing = StatelessState.extensions[pkgNameFromApk]
        StatelessState.extensions[pkgNameFromApk] = ExtensionMetadata(
            pkgName = pkgNameFromApk,
            name = extName,
            versionName = versionName,
            versionCode = versionCode,
            lang = existing?.lang ?: "unknown",
            isNsfw = nsfw == 1,
            isInstalled = true,
            hasUpdate = false,
            iconUrl = existing?.iconUrl ?: "",
            repo = existing?.repo,
            apkName = apkFile.name,
            classFQName = className
        )

        StatelessState.loadedJars[pkgNameFromApk] = jarPath

        logger.info { "Extension $pkgNameFromApk installed and loaded" }
        return jarPath
    }

    /**
     * Load sources from JAR file
     */
    private fun loadSourcesFromJar(jarPath: String, className: String, pkgName: String) {
        logger.debug { "Loading sources from $jarPath, class: $className" }

        when (val instance = loadExtensionSources(jarPath, className)) {
            is Source -> {
                registerSource(instance as CatalogueSource, pkgName)
            }
            is SourceFactory -> {
                instance.createSources().forEach { source ->
                    registerSource(source as CatalogueSource, pkgName)
                }
            }
            else -> {
                throw Exception("Unknown source class type: ${instance.javaClass}")
            }
        }
    }

    /**
     * Register a source in memory
     */
    private fun registerSource(source: CatalogueSource, extensionPkgName: String) {
        logger.debug { "Registering source: ${source.name} (${source.id})" }

        StatelessState.sources[source.id] = SourceMetadata(
            id = source.id,
            name = source.name,
            lang = source.lang,
            extensionPkgName = extensionPkgName,
            isNsfw = false // TODO: Get from extension metadata
        )

        StatelessState.loadedSources[source.id] = source

        logger.info { "Source registered: ${source.name} (${source.id})" }
    }

    /**
     * Reload sources for an installed extension.
     * Clears old source instances and preference caches, then re-instantiates
     * from the existing JAR. The new source constructor reads updated
     * SharedPreferences from disk.
     */
    fun reloadExtensionSources(pkgName: String) {
        val lock = reloadLocks.computeIfAbsent(pkgName) { ReentrantLock() }
        lock.lock()
        try {
            val jarPath = StatelessState.loadedJars[pkgName]
                ?: throw IllegalArgumentException("Extension $pkgName is not installed (no JAR path)")
            val classFQName = StatelessState.extensions[pkgName]?.classFQName
                ?: throw IllegalArgumentException("Extension $pkgName has no class name")

            logger.debug { "Reloading sources for $pkgName" }
            StatelessState.removeSourcesByPkgName(pkgName)
            loadSourcesFromJar(jarPath, classFQName, pkgName)
            logger.info { "Reloaded sources for $pkgName" }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Uninstall an extension
     */
    fun uninstallExtension(pkgName: String): Result<String> {
        return runCatching {
            logger.info { "Uninstalling extension: $pkgName" }

            val jarPath = StatelessState.loadedJars[pkgName]
                ?: throw IllegalArgumentException("Extension $pkgName is not installed")

            // Remove sources + preference cache
            StatelessState.removeSourcesByPkgName(pkgName)

            // Remove JAR file
            File(jarPath).delete()
            StatelessState.loadedJars.remove(pkgName)

            // Update metadata
            StatelessState.extensions[pkgName]?.let { meta ->
                StatelessState.extensions[pkgName] = meta.copy(isInstalled = false)
            }

            logger.info { "Extension $pkgName uninstalled" }
            "Extension $pkgName uninstalled successfully"
        }
    }

    /**
     * Update an extension
     */
    suspend fun updateExtension(pkgName: String): Result<String> {
        logger.info { "Updating extension: $pkgName" }

        // Uninstall first
        uninstallExtension(pkgName).getOrThrow()

        // Reinstall with latest version
        return installExtension(pkgName)
    }

    /**
     * List all extensions
     */
    fun listExtensions(): List<ExtensionMetadata> {
        return StatelessState.extensions.values.toList()
    }

    /**
     * Get extension by package name
     */
    fun getExtension(pkgName: String): ExtensionMetadata? {
        return StatelessState.extensions[pkgName]
    }

    /**
     * Load previously installed extensions from disk on startup
     */
    suspend fun loadInstalledExtensionsFromDisk() = withContext(Dispatchers.IO) {
        logger.info { "Loading installed extensions from disk..." }

        val extensionsDir = File(applicationDirs.extensionsRoot)
        if (!extensionsDir.exists()) {
            logger.debug { "Extensions directory does not exist yet" }
            return@withContext
        }

        val jarFiles = extensionsDir.listFiles { file ->
            file.isFile && file.extension == "jar"
        } ?: emptyArray()

        logger.debug { "Found ${jarFiles.size} JAR files in extensions directory" }

        jarFiles.forEach { jarFile ->
            runCatching {
                // Find corresponding APK
                val apkName = jarFile.nameWithoutExtension + ".apk"
                val apkPath = "${applicationDirs.extensionsRoot}/$apkName"
                val apkFile = File(apkPath)

                if (!apkFile.exists()) {
                    logger.warn { "APK file not found for ${jarFile.name}, skipping" }
                    return@runCatching
                }

                logger.debug { "Loading extension from ${jarFile.name}" }

                // Get package info from APK
                val packageInfo = getPackageInfo(apkPath)
                val pkgName = packageInfo.packageName
                val appInfo = packageInfo.applicationInfo
                val extName = appInfo.nonLocalizedLabel.toString()
                val versionName = packageInfo.versionName
                val versionCode = packageInfo.versionCode

                // Extract metadata
                val classNameFromMeta = appInfo.metaData.getString(METADATA_SOURCE_CLASS)!!
                val nsfw = appInfo.metaData.getString(METADATA_NSFW)?.toIntOrNull() ?: 0

                // Resolve class name
                val className = when {
                    classNameFromMeta.startsWith(".") -> pkgName + classNameFromMeta
                    classNameFromMeta.startsWith("/") -> pkgName + "." + classNameFromMeta.substring(1)
                    else -> classNameFromMeta
                }

                // Load sources from JAR
                loadSourcesFromJar(jarFile.absolutePath, className, pkgName)

                // Update in-memory state
                val existing = StatelessState.extensions[pkgName]
                StatelessState.extensions[pkgName] = ExtensionMetadata(
                    pkgName = pkgName,
                    name = extName,
                    versionName = versionName,
                    versionCode = versionCode,
                    lang = existing?.lang ?: "unknown",
                    isNsfw = nsfw == 1,
                    isInstalled = true,
                    hasUpdate = existing?.let { it.versionCode > versionCode } ?: false,
                    iconUrl = existing?.iconUrl ?: "",
                    repo = existing?.repo,
                    apkName = apkFile.name,
                    classFQName = className
                )

                StatelessState.loadedJars[pkgName] = jarFile.absolutePath

                logger.info { "Loaded extension from disk: $extName ($pkgName)" }
            }.onFailure { error ->
                logger.warn(error) { "Failed to load extension from ${jarFile.name}" }
            }
        }

        val loadedCount = StatelessState.loadedJars.size
        logger.info { "Loaded $loadedCount installed extensions from disk" }
    }

    /**
     * Extract assets from APK and merge them into the JAR file.
     * This is critical for extensions that use i18n or other resources.
     */
    private fun extractAssetsFromApk(
        apkPath: String,
        jarPath: String,
    ) {
        val apkFile = File(apkPath)
        val jarFile = File(jarPath)

        // Extract assets from APK to temp folder
        val assetsFolder = File("${apkFile.parent}/${apkFile.nameWithoutExtension}_assets")
        assetsFolder.mkdir()

        ZipInputStream(apkFile.inputStream()).use { zipInputStream ->
            var zipEntry = zipInputStream.nextEntry
            while (zipEntry != null) {
                if (zipEntry.name.startsWith("assets/") && !zipEntry.isDirectory) {
                    val assetFile = File(assetsFolder, zipEntry.name)
                    assetFile.parentFile.mkdirs()
                    FileOutputStream(assetFile).use { outputStream ->
                        zipInputStream.copyTo(outputStream)
                    }
                }
                zipEntry = zipInputStream.nextEntry
            }
        }

        // Create new JAR with existing content + assets
        val tempJarFile = File("${jarFile.parent}/${jarFile.nameWithoutExtension}_temp.jar")
        ZipInputStream(jarFile.inputStream()).use { jarZipInputStream ->
            ZipOutputStream(FileOutputStream(tempJarFile)).use { jarZipOutputStream ->
                // Copy existing JAR entries (except META-INF to avoid signature issues)
                var zipEntry = jarZipInputStream.nextEntry
                while (zipEntry != null) {
                    if (!zipEntry.name.startsWith("META-INF/")) {
                        jarZipOutputStream.putNextEntry(ZipEntry(zipEntry.name))
                        jarZipInputStream.copyTo(jarZipOutputStream)
                    }
                    zipEntry = jarZipInputStream.nextEntry
                }

                // Add assets to JAR
                assetsFolder.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        jarZipOutputStream.putNextEntry(
                            ZipEntry(file.relativeTo(assetsFolder).toString().replace("\\", "/"))
                        )
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(jarZipOutputStream)
                        }
                        jarZipOutputStream.closeEntry()
                    }
                }
            }
        }

        // Replace original JAR with new one
        jarFile.delete()
        tempJarFile.renameTo(jarFile)

        // Clean up temp folder
        assetsFolder.deleteRecursively()
    }
}
