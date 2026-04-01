package moe.radar.mihon_gateway.service

import eu.kanade.tachiyomi.source.ConfigurableSource
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import moe.radar.mihon_gateway.extension.ExtensionManager
import moe.radar.mihon_gateway.proto.GetSourcePreferencesRequest
import moe.radar.mihon_gateway.proto.SetSourcePreferenceRequest
import moe.radar.mihon_gateway.proto.SourcePreference
import moe.radar.mihon_gateway.state.StatelessState
import moe.radar.mihon_gateway.test.BaseTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Integration tests for source preference RPCs.
 * Uses a real configurable extension (MangaDex) following the project's
 * existing integration test pattern.
 */
@DisplayName("Source Preferences Tests")
@Tag("integration")
class SourcePreferencesTest : BaseTest() {

    companion object {
        private var configurableSourceId: Long = 0
        private var nonConfigurableSourceId: Long = 0
        private lateinit var extensionPkgName: String

        @BeforeAll
        @JvmStatic
        fun installExtension(): Unit = runBlocking {
            // Fetch and install MangaDex (has many configurable preferences)
            ExtensionManager.fetchExtensionsFromGitHub()
            val pkgName = "eu.kanade.tachiyomi.extension.all.mangadex"
            val result = ExtensionManager.installExtension(pkgName)
            assertTrue(result.isSuccess, "MangaDex installation should succeed: ${result.exceptionOrNull()?.message}")
            extensionPkgName = pkgName

            // Find a configurable source from this extension
            val configurableEntry = StatelessState.loadedSources.entries.find { (_, source) ->
                source is ConfigurableSource &&
                    StatelessState.sources[source.id]?.extensionPkgName == pkgName
            }
            assertNotNull(configurableEntry, "MangaDex should have at least one configurable source")
            configurableSourceId = configurableEntry.key

            // Install a non-configurable extension for negative test
            val nonConfigPkg = "eu.kanade.tachiyomi.extension.en.asurascans"
            val ncResult = ExtensionManager.installExtension(nonConfigPkg)
            assertTrue(ncResult.isSuccess, "AsuraScans installation should succeed")

            val nonConfigEntry = StatelessState.loadedSources.entries.find { (_, source) ->
                source !is ConfigurableSource &&
                    StatelessState.sources[source.id]?.extensionPkgName == nonConfigPkg
            }
            if (nonConfigEntry != null) {
                nonConfigurableSourceId = nonConfigEntry.key
            }
        }
    }

    private val service = MangaSourceServiceImpl()

    @BeforeEach
    fun clearPrefCache() {
        StatelessState.preferenceScreenCache.clear()
    }

    @Test
    @DisplayName("getSourcePreferences returns preferences for configurable source")
    fun testGetSourcePreferencesReturnsPreferences(): Unit = runBlocking {
        val request = GetSourcePreferencesRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .build()

        val response = service.getSourcePreferences(request)

        assertTrue(response.preferencesCount > 0, "Should return preferences for configurable source")
        response.preferencesList.forEach { pref ->
            assertTrue(pref.key.isNotEmpty(), "Each preference should have a key")
        }
    }

    @Test
    @DisplayName("getSourcePreferences returns correct preference types")
    fun testGetSourcePreferencesTypes(): Unit = runBlocking {
        val request = GetSourcePreferencesRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .build()

        val response = service.getSourcePreferences(request)

        // MangaDex should have various preference types
        val hasTypedPreference = response.preferencesList.any { pref ->
            pref.preferenceCase != SourcePreference.PreferenceCase.PREFERENCE_NOT_SET
        }
        assertTrue(hasTypedPreference, "Should have at least one typed preference")

        // Verify each preference has a valid default value type
        response.preferencesList.forEach { pref ->
            assertTrue(
                pref.defaultValueType in listOf("String", "Boolean", "Set<String>"),
                "Preference '${pref.key}' should have valid defaultValueType, got '${pref.defaultValueType}'"
            )
        }
    }

    @Test
    @DisplayName("getSourcePreferences throws for non-configurable source")
    fun testGetSourcePreferencesNonConfigurable(): Unit = runBlocking {
        if (nonConfigurableSourceId == 0L) {
            println("Skipping: no non-configurable source available")
            return@runBlocking
        }

        val request = GetSourcePreferencesRequest.newBuilder()
            .setSourceId(nonConfigurableSourceId)
            .build()

        val ex = assertFailsWith<StatusException> {
            service.getSourcePreferences(request)
        }
        assertEquals(io.grpc.Status.FAILED_PRECONDITION.code, ex.status.code)
    }

    @Test
    @DisplayName("setSourcePreference persists boolean value")
    fun testSetBooleanPreference(): Unit = runBlocking {
        // First get preferences to find a boolean one
        val getRequest = GetSourcePreferencesRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .build()
        val prefs = service.getSourcePreferences(getRequest)

        val boolPref = prefs.preferencesList.find { it.defaultValueType == "Boolean" }
        if (boolPref == null) {
            println("Skipping: no boolean preference found")
            return@runBlocking
        }

        // Get current value and flip it
        val currentValue = when {
            boolPref.hasSwitchPreference() -> boolPref.switchPreference.currentValue
            boolPref.hasCheckBoxPreference() -> boolPref.checkBoxPreference.currentValue
            else -> false
        }
        val newValue = !currentValue

        // Set the value
        val setRequest = SetSourcePreferenceRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .setKey(boolPref.key)
            .setBoolValue(newValue)
            .build()
        val response = service.setSourcePreference(setRequest)

        // Verify the returned preferences reflect the new value
        val updatedPref = response.preferencesList.find { it.key == boolPref.key }
        assertNotNull(updatedPref, "Updated preference should be in response")
        val updatedValue = when {
            updatedPref.hasSwitchPreference() -> updatedPref.switchPreference.currentValue
            updatedPref.hasCheckBoxPreference() -> updatedPref.checkBoxPreference.currentValue
            else -> !newValue // force fail
        }
        assertEquals(newValue, updatedValue, "Value should be updated")
    }

    @Test
    @DisplayName("setSourcePreference persists string value")
    fun testSetStringPreference(): Unit = runBlocking {
        val getRequest = GetSourcePreferencesRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .build()
        val prefs = service.getSourcePreferences(getRequest)

        // Find a ListPreference
        val listPref = prefs.preferencesList.find { it.hasListPreference() && it.listPreference.entryValuesCount > 1 }
        if (listPref == null) {
            println("Skipping: no list preference with multiple values found")
            return@runBlocking
        }

        // Pick a different entry value than the current
        val currentValue = listPref.listPreference.currentValue
        val newValue = listPref.listPreference.entryValuesList.first { it != currentValue }

        val setRequest = SetSourcePreferenceRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .setKey(listPref.key)
            .setStringValue(newValue)
            .build()
        val response = service.setSourcePreference(setRequest)

        val updatedPref = response.preferencesList.find { it.key == listPref.key }
        assertNotNull(updatedPref)
        assertEquals(newValue, updatedPref.listPreference.currentValue, "String value should be updated")
    }

    @Test
    @DisplayName("setSourcePreference rejects type mismatch")
    fun testSetPreferenceTypeMismatch(): Unit = runBlocking {
        val getRequest = GetSourcePreferencesRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .build()
        val prefs = service.getSourcePreferences(getRequest)

        val boolPref = prefs.preferencesList.find { it.defaultValueType == "Boolean" }
        if (boolPref == null) {
            println("Skipping: no boolean preference found")
            return@runBlocking
        }

        // Send string value for a boolean preference
        val setRequest = SetSourcePreferenceRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .setKey(boolPref.key)
            .setStringValue("wrong_type")
            .build()

        val ex = assertFailsWith<StatusException> {
            service.setSourcePreference(setRequest)
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
    }

    @Test
    @DisplayName("setSourcePreference rejects unknown key")
    fun testSetPreferenceUnknownKey(): Unit = runBlocking {
        val setRequest = SetSourcePreferenceRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .setKey("nonexistent_key_12345")
            .setBoolValue(true)
            .build()

        val ex = assertFailsWith<StatusException> {
            service.setSourcePreference(setRequest)
        }
        assertEquals(io.grpc.Status.NOT_FOUND.code, ex.status.code)
    }

    @Test
    @DisplayName("setSourcePreference reloads source")
    fun testSetPreferenceReloadsSource(): Unit = runBlocking {
        val getRequest = GetSourcePreferencesRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .build()
        val prefs = service.getSourcePreferences(getRequest)

        val boolPref = prefs.preferencesList.find { it.defaultValueType == "Boolean" }
        if (boolPref == null) {
            println("Skipping: no boolean preference found")
            return@runBlocking
        }

        val originalInstance = StatelessState.loadedSources[configurableSourceId]

        val currentValue = when {
            boolPref.hasSwitchPreference() -> boolPref.switchPreference.currentValue
            boolPref.hasCheckBoxPreference() -> boolPref.checkBoxPreference.currentValue
            else -> false
        }

        val setRequest = SetSourcePreferenceRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .setKey(boolPref.key)
            .setBoolValue(!currentValue)
            .build()
        service.setSourcePreference(setRequest)

        val reloadedInstance = StatelessState.loadedSources[configurableSourceId]
        assertNotSame(originalInstance, reloadedInstance, "Source should be a new instance after set")
    }

    @Test
    @DisplayName("preferences survive source reload")
    fun testPreferencesSurviveReload(): Unit = runBlocking {
        val getRequest = GetSourcePreferencesRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .build()
        val prefs = service.getSourcePreferences(getRequest)

        val boolPref = prefs.preferencesList.find { it.defaultValueType == "Boolean" }
        if (boolPref == null) {
            println("Skipping: no boolean preference found")
            return@runBlocking
        }

        val currentValue = when {
            boolPref.hasSwitchPreference() -> boolPref.switchPreference.currentValue
            boolPref.hasCheckBoxPreference() -> boolPref.checkBoxPreference.currentValue
            else -> false
        }
        val newValue = !currentValue

        // Set the value (which also triggers reload)
        val setRequest = SetSourcePreferenceRequest.newBuilder()
            .setSourceId(configurableSourceId)
            .setKey(boolPref.key)
            .setBoolValue(newValue)
            .build()
        service.setSourcePreference(setRequest)

        // Manually reload again
        ExtensionManager.reloadExtensionSources(extensionPkgName)
        StatelessState.preferenceScreenCache.clear()

        // Read preferences from the fresh source
        val freshPrefs = service.getSourcePreferences(getRequest)
        val freshBoolPref = freshPrefs.preferencesList.find { it.key == boolPref.key }
        assertNotNull(freshBoolPref, "Preference should still exist after reload")

        val freshValue = when {
            freshBoolPref.hasSwitchPreference() -> freshBoolPref.switchPreference.currentValue
            freshBoolPref.hasCheckBoxPreference() -> freshBoolPref.checkBoxPreference.currentValue
            else -> !newValue
        }
        assertEquals(newValue, freshValue, "Value should persist across source reload")
    }
}
