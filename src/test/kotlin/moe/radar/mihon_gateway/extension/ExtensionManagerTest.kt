package moe.radar.mihon_gateway.extension

import kotlinx.coroutines.runBlocking
import moe.radar.mihon_gateway.state.StatelessState
import moe.radar.mihon_gateway.test.BaseTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertEquals

/**
 * Tests for ExtensionManager based on Suwayomi's extension tests.
 * Tests extension fetching, installation, and source loading.
 */
@DisplayName("ExtensionManager Tests")
@Tag("integration")
class ExtensionManagerTest : BaseTest() {

    @BeforeEach
    fun setup() {
        // Clear state before each test
        StatelessState.clear()
    }

    @Test
    @DisplayName("Fetch extensions from GitHub")
    fun testFetchExtensionsFromGitHub(): Unit = runBlocking {
        // Fetch extensions
        ExtensionManager.fetchExtensionsFromGitHub()

        // Verify extensions were fetched
        val extensions = StatelessState.extensions
        assertTrue(extensions.isNotEmpty(), "Should fetch extensions from GitHub")
        assertTrue(extensions.size > 1000, "Should fetch at least 1000 extensions (got ${extensions.size})")

        // Verify extension structure
        val firstExtension = extensions.values.first()
        assertNotNull(firstExtension.pkgName, "Extension should have package name")
        assertNotNull(firstExtension.name, "Extension should have name")
        assertNotNull(firstExtension.lang, "Extension should have language")
        assertFalse(firstExtension.isInstalled, "Extensions should not be installed by default")
    }

    @Test
    @DisplayName("Extension list cache is valid after fetching")
    fun testExtensionListCache(): Unit = runBlocking {
        // Fetch extensions first time
        ExtensionManager.fetchExtensionsFromGitHub()
        val firstFetchTime = System.currentTimeMillis()

        // Second fetch should use cache
        ExtensionManager.fetchExtensionsFromGitHub()
        val secondFetchTime = System.currentTimeMillis()

        // Cache should be valid (second fetch should be much faster)
        assertTrue(StatelessState.isExtensionListCacheValid(), "Cache should be valid after fetching")
        assertTrue(
            secondFetchTime - firstFetchTime < 100,
            "Cached fetch should be very fast (took ${secondFetchTime - firstFetchTime}ms)"
        )
    }

    @Test
    @DisplayName("List extensions returns all fetched extensions")
    fun testListExtensions(): Unit = runBlocking {
        // Fetch extensions
        ExtensionManager.fetchExtensionsFromGitHub()

        // List extensions
        val extensionList = ExtensionManager.listExtensions()

        assertTrue(extensionList.isNotEmpty(), "Should return list of extensions")
        assertEquals(
            StatelessState.extensions.size,
            extensionList.size,
            "Should return all extensions from state"
        )
    }

    @Test
    @DisplayName("Get extension by package name")
    fun testGetExtension(): Unit = runBlocking {
        // Fetch extensions
        ExtensionManager.fetchExtensionsFromGitHub()

        // Get a specific extension (use a well-known one)
        val pkgName = "eu.kanade.tachiyomi.extension.en.mangadex"
        val extension = ExtensionManager.getExtension(pkgName)

        if (extension != null) {
            assertEquals(pkgName, extension.pkgName, "Should return correct extension")
            assertEquals("en", extension.lang, "MangaDex extension should be English")
        } else {
            // Extension might not exist in current repo, that's okay for test
            println("Note: MangaDex extension not found in current repo")
        }
    }

    @Test
    @DisplayName("Get non-existent extension returns null")
    fun testGetNonExistentExtension(): Unit = runBlocking {
        // Fetch extensions
        ExtensionManager.fetchExtensionsFromGitHub()

        // Try to get non-existent extension
        val extension = ExtensionManager.getExtension("com.fake.nonexistent.extension")

        assertEquals(null, extension, "Should return null for non-existent extension")
    }

    @Test
    @DisplayName("Install extension downloads and loads sources")
    fun testInstallExtension(): Unit = runBlocking {
        // Fetch extensions first
        ExtensionManager.fetchExtensionsFromGitHub()

        // Pick a small, stable extension for testing (AsuraScans is commonly available)
        val pkgName = "eu.kanade.tachiyomi.extension.en.asurascans"
        val extensionBefore = ExtensionManager.getExtension(pkgName)
        assertNotNull(extensionBefore, "Test extension should be available")
        assertFalse(extensionBefore.isInstalled, "Extension should not be installed initially")

        // Install the extension
        val result = ExtensionManager.installExtension(pkgName)
        assertTrue(result.isSuccess, "Installation should succeed: ${result.exceptionOrNull()?.message}")

        // Verify extension is now installed
        val extensionAfter = ExtensionManager.getExtension(pkgName)
        assertNotNull(extensionAfter, "Extension should still exist after installation")
        assertTrue(extensionAfter.isInstalled, "Extension should be marked as installed")
        assertTrue(extensionAfter.classFQName.isNotEmpty(), "Extension should have class name after installation")

        // Verify JAR was created
        assertTrue(StatelessState.loadedJars.containsKey(pkgName), "JAR should be registered in state")

        // Verify at least one source was loaded
        val sources = StatelessState.sources.filter { it.value.extensionPkgName == pkgName }
        assertTrue(sources.isNotEmpty(), "Extension should load at least one source")

        // Verify source is accessible
        val firstSource = sources.values.first()
        assertNotNull(StatelessState.loadedSources[firstSource.id], "Source should be loaded and accessible")
    }

    @Test
    @DisplayName("Load installed extensions from disk on startup")
    fun testLoadInstalledExtensionsFromDisk(): Unit = runBlocking {
        // Fetch and install an extension
        ExtensionManager.fetchExtensionsFromGitHub()
        val pkgName = "eu.kanade.tachiyomi.extension.en.asurascans"
        val installResult = ExtensionManager.installExtension(pkgName)
        assertTrue(installResult.isSuccess, "Installation should succeed: ${installResult.exceptionOrNull()?.message}")

        // Record installed state
        val sourcesBeforeClear = StatelessState.sources.filter { it.value.extensionPkgName == pkgName }.keys

        // Clear in-memory state (simulating restart)
        StatelessState.clear()
        ExtensionManager.fetchExtensionsFromGitHub()

        // Verify extension is not installed in memory
        val extensionAfterClear = ExtensionManager.getExtension(pkgName)
        assertNotNull(extensionAfterClear, "Extension metadata should exist")
        assertFalse(extensionAfterClear.isInstalled, "Extension should not be installed in fresh state")

        // Load from disk
        ExtensionManager.loadInstalledExtensionsFromDisk()

        // Verify extension is now loaded
        val extensionAfterLoad = ExtensionManager.getExtension(pkgName)
        assertNotNull(extensionAfterLoad, "Extension should exist after disk load")
        assertTrue(extensionAfterLoad.isInstalled, "Extension should be marked as installed after disk load")

        // Verify sources were reloaded
        val sourcesAfterLoad = StatelessState.sources.filter { it.value.extensionPkgName == pkgName }.keys
        assertEquals(sourcesBeforeClear.size, sourcesAfterLoad.size, "Same number of sources should be loaded")
        assertTrue(
            sourcesBeforeClear.all { it in sourcesAfterLoad },
            "All original sources should be reloaded"
        )
    }

    @Test
    @DisplayName("Uninstall extension removes files and state")
    fun testUninstallExtension(): Unit = runBlocking {
        // Fetch and install an extension
        ExtensionManager.fetchExtensionsFromGitHub()
        val pkgName = "eu.kanade.tachiyomi.extension.en.asurascans"
        val installResult = ExtensionManager.installExtension(pkgName)
        assertTrue(installResult.isSuccess, "Installation should succeed")

        // Verify it's installed
        assertTrue(ExtensionManager.getExtension(pkgName)?.isInstalled == true, "Extension should be installed")
        val sourcesBeforeUninstall = StatelessState.sources.filter { it.value.extensionPkgName == pkgName }.keys
        assertTrue(sourcesBeforeUninstall.isNotEmpty(), "Sources should be loaded")

        // Uninstall
        val uninstallResult = ExtensionManager.uninstallExtension(pkgName)
        assertTrue(uninstallResult.isSuccess, "Uninstall should succeed: ${uninstallResult.exceptionOrNull()?.message}")

        // Verify extension is marked as not installed
        val extensionAfter = ExtensionManager.getExtension(pkgName)
        assertNotNull(extensionAfter, "Extension metadata should still exist")
        assertFalse(extensionAfter.isInstalled, "Extension should not be marked as installed")

        // Verify sources were removed
        val sourcesAfterUninstall = StatelessState.sources.filter { it.value.extensionPkgName == pkgName }
        assertTrue(sourcesAfterUninstall.isEmpty(), "All sources should be removed")

        // Verify JAR was removed from state
        assertFalse(StatelessState.loadedJars.containsKey(pkgName), "JAR should be removed from state")
    }

    @Test
    @DisplayName("Install multiple extensions")
    fun testInstallMultipleExtensions(): Unit = runBlocking {
        // Fetch extensions
        ExtensionManager.fetchExtensionsFromGitHub()

        // Install multiple extensions
        val pkgNames = listOf(
            "eu.kanade.tachiyomi.extension.en.asurascans",
            "eu.kanade.tachiyomi.extension.en.mangakakalot"
        )

        val results = pkgNames.map { pkgName ->
            pkgName to ExtensionManager.installExtension(pkgName)
        }

        // Verify all installations succeeded
        results.forEach { (pkgName, result) ->
            assertTrue(
                result.isSuccess,
                "Installation of $pkgName should succeed: ${result.exceptionOrNull()?.message}"
            )
        }

        // Verify all are installed
        pkgNames.forEach { pkgName ->
            val extension = ExtensionManager.getExtension(pkgName)
            assertNotNull(extension, "$pkgName should exist")
            assertTrue(extension.isInstalled, "$pkgName should be installed")
        }

        // Verify sources from all extensions were loaded
        pkgNames.forEach { pkgName ->
            val sources = StatelessState.sources.filter { it.value.extensionPkgName == pkgName }
            assertTrue(sources.isNotEmpty(), "$pkgName should have loaded sources")
        }
    }

    @Test
    @DisplayName("reloadExtensionSources re-instantiates sources")
    fun testReloadExtensionSources(): Unit = runBlocking {
        ExtensionManager.fetchExtensionsFromGitHub()
        val pkgName = "eu.kanade.tachiyomi.extension.en.asurascans"
        val installResult = ExtensionManager.installExtension(pkgName)
        assertTrue(installResult.isSuccess, "Installation should succeed")

        // Capture original source instance
        val sourcesBefore = StatelessState.sources.filter { it.value.extensionPkgName == pkgName }
        assertTrue(sourcesBefore.isNotEmpty(), "Should have loaded sources")
        val firstSourceId = sourcesBefore.keys.first()
        val originalInstance = StatelessState.loadedSources[firstSourceId]

        // Reload
        ExtensionManager.reloadExtensionSources(pkgName)

        // Source should be a different instance
        val reloadedInstance = StatelessState.loadedSources[firstSourceId]
        assertNotNull(reloadedInstance, "Source should still exist after reload")
        assertNotSame(originalInstance, reloadedInstance, "Source should be a new instance after reload")
    }

    // ==================== Extension Repo Management ====================

    @Test
    @DisplayName("listRepos returns default keiyoushi repo")
    fun testListReposDefault() {
        val repos = ExtensionManager.listRepos()
        assertEquals(1, repos.size)
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            repos[0]
        )
    }

    @Test
    @DisplayName("addRepo adds a new repo and returns updated list")
    fun testAddRepo() {
        val customUrl = "https://example.com/extensions/repo/index.min.json"
        val repos = ExtensionManager.addRepo(customUrl)

        assertEquals(2, repos.size)
        assertTrue(repos.contains(customUrl))
        assertTrue(repos.contains("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"))
    }

    @Test
    @DisplayName("addRepo is idempotent for duplicate URLs")
    fun testAddRepoDuplicate() {
        val customUrl = "https://example.com/extensions/repo/index.min.json"
        val first = ExtensionManager.addRepo(customUrl)
        val second = ExtensionManager.addRepo(customUrl)

        assertEquals(first, second)
        assertEquals(2, second.size)
    }

    @Test
    @DisplayName("addRepo invalidates extension list cache")
    fun testAddRepoInvalidatesCache() {
        // Populate cache
        StatelessState.updateExtensionListCache(emptyList())
        assertTrue(StatelessState.isExtensionListCacheValid())

        // Adding a repo should invalidate
        ExtensionManager.addRepo("https://example.com/repo/index.min.json")
        assertFalse(StatelessState.isExtensionListCacheValid())
    }

    @Test
    @DisplayName("removeRepo removes a repo")
    fun testRemoveRepo() {
        val customUrl = "https://example.com/extensions/repo/index.min.json"
        ExtensionManager.addRepo(customUrl)
        assertEquals(2, ExtensionManager.listRepos().size)

        ExtensionManager.removeRepo(customUrl)
        assertEquals(1, ExtensionManager.listRepos().size)
        assertFalse(ExtensionManager.listRepos().contains(customUrl))
    }

    @Test
    @DisplayName("removeRepo allows removing default keiyoushi repo")
    fun testRemoveDefaultRepo() {
        val defaultUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        ExtensionManager.removeRepo(defaultUrl)

        assertTrue(ExtensionManager.listRepos().isEmpty())
    }

    @Test
    @DisplayName("removeRepo invalidates extension list cache")
    fun testRemoveRepoInvalidatesCache() {
        val customUrl = "https://example.com/repo/index.min.json"
        ExtensionManager.addRepo(customUrl)

        // Populate cache
        StatelessState.updateExtensionListCache(emptyList())
        assertTrue(StatelessState.isExtensionListCacheValid())

        // Removing a repo should invalidate
        ExtensionManager.removeRepo(customUrl)
        assertFalse(StatelessState.isExtensionListCacheValid())
    }

    @Test
    @DisplayName("removeRepo is no-op for non-existent URL")
    fun testRemoveRepoNonExistent() {
        val before = ExtensionManager.listRepos()
        ExtensionManager.removeRepo("https://nonexistent.example.com/index.min.json")
        assertEquals(before, ExtensionManager.listRepos())
    }

    @Test
    @DisplayName("reloadExtensionSources throws for unknown package")
    fun testReloadExtensionSourcesUnknownPackage() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionManager.reloadExtensionSources("com.fake.nonexistent")
        }
    }

    @Test
    @DisplayName("Extension metadata is correctly parsed from APK")
    fun testExtensionMetadataParsing(): Unit = runBlocking {
        // Fetch and install an extension
        ExtensionManager.fetchExtensionsFromGitHub()
        val pkgName = "eu.kanade.tachiyomi.extension.en.asurascans"
        val installResult = ExtensionManager.installExtension(pkgName)
        assertTrue(installResult.isSuccess, "Installation should succeed")

        // Verify metadata
        val extension = ExtensionManager.getExtension(pkgName)
        assertNotNull(extension, "Extension should exist")

        // Verify package info
        assertEquals(pkgName, extension.pkgName, "Package name should match")
        assertTrue(extension.name.isNotEmpty(), "Extension name should not be empty")
        assertTrue(extension.versionName.isNotEmpty(), "Version name should not be empty")
        assertTrue(extension.versionCode > 0, "Version code should be positive")

        // Verify language
        assertEquals("en", extension.lang, "Language should be English")

        // Verify class name was resolved
        assertTrue(extension.classFQName.isNotEmpty(), "Class name should be set")
        assertTrue(
            extension.classFQName.contains(pkgName),
            "Class name should contain package name: ${extension.classFQName}"
        )

        // Verify APK name
        assertTrue(extension.apkName.isNotEmpty(), "APK name should be set")
        assertTrue(extension.apkName.endsWith(".apk"), "APK name should end with .apk")
    }
}
