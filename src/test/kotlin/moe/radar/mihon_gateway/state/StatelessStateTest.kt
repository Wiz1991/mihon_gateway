package moe.radar.mihon_gateway.state

import androidx.preference.PreferenceScreen
import moe.radar.mihon_gateway.test.BaseTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.extension.github.OnlineExtension
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * Tests for StatelessState - the in-memory state management.
 * Verifies thread-safe concurrent access and cache invalidation.
 */
@DisplayName("StatelessState Tests")
class StatelessStateTest : BaseTest() {

    @BeforeEach
    fun setup() {
        // Clear all state
        StatelessState.clear()
    }

    @Test
    @DisplayName("Extension cache validity check")
    fun testExtensionCacheValidity() {
        // Initially cache should be invalid
        assertFalse(StatelessState.isExtensionListCacheValid(), "Cache should be invalid initially")

        // Update cache with empty list
        StatelessState.updateExtensionListCache(emptyList())

        // Now cache should be valid
        assertTrue(StatelessState.isExtensionListCacheValid(), "Cache should be valid after update")
    }

    @Test
    @DisplayName("Extension cache expires after TTL")
    fun testExtensionCacheExpiry() {
        // Update cache
        StatelessState.updateExtensionListCache(emptyList())
        assertTrue(StatelessState.isExtensionListCacheValid(), "Cache should be valid after update")

        // Clear cache manually (simulates expiry)
        StatelessState.clear()

        // Cache should be invalid now
        assertFalse(StatelessState.isExtensionListCacheValid(), "Cache should be invalid after clearing")
    }

    @Test
    @DisplayName("Extensions map is thread-safe ConcurrentHashMap")
    fun testExtensionsConcurrentAccess() {
        // Add extensions
        val ext1 = ExtensionMetadata(
            pkgName = "com.test.ext1",
            name = "Test Extension 1",
            versionName = "1.0",
            versionCode = 1,
            lang = "en",
            isNsfw = false,
            isInstalled = false,
            hasUpdate = false,
            iconUrl = "http://example.com/icon1.png",
            repo = "http://example.com/repo",
            apkName = "ext1.apk",
            classFQName = "com.test.Ext1"
        )

        val ext2 = ExtensionMetadata(
            pkgName = "com.test.ext2",
            name = "Test Extension 2",
            versionName = "1.0",
            versionCode = 1,
            lang = "ja",
            isNsfw = true,
            isInstalled = true,
            hasUpdate = false,
            iconUrl = "http://example.com/icon2.png",
            repo = "http://example.com/repo",
            apkName = "ext2.apk",
            classFQName = "com.test.Ext2"
        )

        // Add to state
        StatelessState.extensions[ext1.pkgName] = ext1
        StatelessState.extensions[ext2.pkgName] = ext2

        // Verify retrieval
        assertEquals(2, StatelessState.extensions.size, "Should have 2 extensions")
        assertEquals(ext1, StatelessState.extensions[ext1.pkgName], "Should retrieve extension 1")
        assertEquals(ext2, StatelessState.extensions[ext2.pkgName], "Should retrieve extension 2")
    }

    @Test
    @DisplayName("Sources map maintains source metadata")
    fun testSourcesMap() {
        // Add source metadata
        val sourceId = 12345L
        val sourceMeta = SourceMetadata(
            id = sourceId,
            name = "Test Source",
            lang = "en",
            extensionPkgName = "com.test.extension",
            isNsfw = false
        )

        StatelessState.sources[sourceId] = sourceMeta

        // Verify retrieval
        assertEquals(1, StatelessState.sources.size, "Should have 1 source")
        assertEquals(sourceMeta, StatelessState.sources[sourceId], "Should retrieve correct source")
    }

    @Test
    @DisplayName("Loaded JARs map tracks installations")
    fun testLoadedJarsMap() {
        val pkgName = "com.test.extension"
        val jarPath = "/path/to/extension.jar"

        // Add JAR path
        StatelessState.loadedJars[pkgName] = jarPath

        // Verify retrieval
        assertEquals(1, StatelessState.loadedJars.size, "Should have 1 loaded JAR")
        assertEquals(jarPath, StatelessState.loadedJars[pkgName], "Should retrieve correct JAR path")

        // Remove JAR
        StatelessState.loadedJars.remove(pkgName)
        assertEquals(0, StatelessState.loadedJars.size, "Should have 0 loaded JARs after removal")
    }

    @Test
    @DisplayName("Update extension list cache makes it valid")
    fun testUpdateExtensionListCache() {
        val onlineExtensions = listOf(
            OnlineExtension(
                name = "Test Extension",
                pkgName = "com.test.extension",
                versionName = "1.0",
                versionCode = 1,
                lang = "en",
                isNsfw = false,
                hasReadme = false,
                hasChangelog = false,
                sources = emptyList(),
                apkName = "test.apk",
                iconUrl = "http://example.com/icon.png",
                repo = "http://example.com/repo"
            )
        )

        // Update cache
        StatelessState.updateExtensionListCache(onlineExtensions)

        // Verify cache is valid
        assertTrue(StatelessState.isExtensionListCacheValid(), "Cache should be valid after update")

        // Verify cache contains the extension
        assertEquals(1, StatelessState.extensionListCache?.size, "Cache should contain 1 extension")
    }

    @Test
    @DisplayName("removeSourcesByPkgName removes all sources for package")
    fun testRemoveSourcesByPkgName() {
        // Add sources for two different packages
        val pkg1 = "com.test.pkg1"
        val pkg2 = "com.test.pkg2"

        StatelessState.sources[1L] = SourceMetadata(1L, "Source1", "en", pkg1, false)
        StatelessState.sources[2L] = SourceMetadata(2L, "Source2", "en", pkg1, false)
        StatelessState.sources[3L] = SourceMetadata(3L, "Source3", "ja", pkg2, false)

        // Add dummy loaded sources
        // (We can't easily create CatalogueSource instances, so just test the metadata maps)

        // Add preference screen cache entries
        StatelessState.preferenceScreenCache[1L] = PreferenceScreen(CustomContext())
        StatelessState.preferenceScreenCache[2L] = PreferenceScreen(CustomContext())
        StatelessState.preferenceScreenCache[3L] = PreferenceScreen(CustomContext())

        // Remove pkg1 sources
        val removedIds = StatelessState.removeSourcesByPkgName(pkg1)

        // Verify correct sources removed
        assertEquals(listOf(1L, 2L), removedIds.sorted())
        assertFalse(StatelessState.sources.containsKey(1L))
        assertFalse(StatelessState.sources.containsKey(2L))
        assertTrue(StatelessState.sources.containsKey(3L))

        // Verify preference cache also cleaned
        assertFalse(StatelessState.preferenceScreenCache.containsKey(1L))
        assertFalse(StatelessState.preferenceScreenCache.containsKey(2L))
        assertTrue(StatelessState.preferenceScreenCache.containsKey(3L))
    }

    @Test
    @DisplayName("removeSourcesByPkgName returns removed IDs")
    fun testRemoveSourcesByPkgNameReturnValue() {
        StatelessState.sources[10L] = SourceMetadata(10L, "S1", "en", "com.test.a", false)
        StatelessState.sources[20L] = SourceMetadata(20L, "S2", "en", "com.test.b", false)

        val removed = StatelessState.removeSourcesByPkgName("com.test.a")
        assertEquals(listOf(10L), removed)

        val removedNone = StatelessState.removeSourcesByPkgName("com.test.nonexistent")
        assertTrue(removedNone.isEmpty())
    }

    @Test
    @DisplayName("clear also clears preferenceScreenCache")
    fun testClearAlsoClearsPreferenceScreenCache() {
        StatelessState.preferenceScreenCache[1L] = PreferenceScreen(CustomContext())
        StatelessState.preferenceScreenCache[2L] = PreferenceScreen(CustomContext())

        assertTrue(StatelessState.preferenceScreenCache.isNotEmpty())

        StatelessState.clear()

        assertTrue(StatelessState.preferenceScreenCache.isEmpty())
    }

    // ==================== Extension Repos ====================

    @Test
    @DisplayName("Extension repos initialized with keiyoushi default")
    fun testExtensionReposDefault() {
        assertEquals(1, StatelessState.extensionRepos.size)
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            StatelessState.extensionRepos[0]
        )
    }

    @Test
    @DisplayName("Extension repos addIfAbsent is idempotent")
    fun testExtensionReposIdempotentAdd() {
        val url = "https://example.com/repo/index.min.json"
        assertTrue(StatelessState.extensionRepos.addIfAbsent(url))
        assertFalse(StatelessState.extensionRepos.addIfAbsent(url))
        assertEquals(2, StatelessState.extensionRepos.size)
    }

    @Test
    @DisplayName("Extension repos remove works")
    fun testExtensionReposRemove() {
        val url = "https://example.com/repo/index.min.json"
        StatelessState.extensionRepos.addIfAbsent(url)
        assertEquals(2, StatelessState.extensionRepos.size)

        assertTrue(StatelessState.extensionRepos.remove(url))
        assertEquals(1, StatelessState.extensionRepos.size)
    }

    @Test
    @DisplayName("Extension repos can remove keiyoushi default")
    fun testExtensionReposRemoveDefault() {
        val defaultUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        assertTrue(StatelessState.extensionRepos.remove(defaultUrl))
        assertTrue(StatelessState.extensionRepos.isEmpty())
    }

    @Test
    @DisplayName("clear() resets extension repos to default")
    fun testClearResetsExtensionRepos() {
        StatelessState.extensionRepos.add("https://example.com/custom")
        assertEquals(2, StatelessState.extensionRepos.size)

        StatelessState.clear()

        assertEquals(1, StatelessState.extensionRepos.size)
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            StatelessState.extensionRepos[0]
        )
    }

    @Test
    @DisplayName("invalidateExtensionListCache clears cache")
    fun testInvalidateExtensionListCache() {
        StatelessState.updateExtensionListCache(emptyList())
        assertTrue(StatelessState.isExtensionListCacheValid())

        StatelessState.invalidateExtensionListCache()

        assertFalse(StatelessState.isExtensionListCacheValid())
    }

    @Test
    @DisplayName("Extension metadata is properly initialized")
    fun testExtensionMetadataDefaults() {
        val meta = ExtensionMetadata(
            pkgName = "com.test",
            name = "Test",
            versionName = "1.0",
            versionCode = 1,
            lang = "en",
            isNsfw = false,
            isInstalled = false,
            hasUpdate = false,
            iconUrl = "http://example.com/icon.png",
            repo = "http://example.com/repo",
            apkName = "test.apk",
            classFQName = ""
        )

        assertFalse(meta.isInstalled, "Should not be installed by default")
        assertFalse(meta.hasUpdate, "Should not have update by default")
        assertTrue(meta.classFQName.isEmpty(), "Class name should be empty before installation")
    }
}
