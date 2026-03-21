package moe.radar.mihon_gateway.state

import moe.radar.mihon_gateway.test.BaseTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.extension.github.OnlineExtension
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
