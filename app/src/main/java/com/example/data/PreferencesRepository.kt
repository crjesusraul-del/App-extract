package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nxDumpDataStore: DataStore<Preferences> by preferencesDataStore(name = "nxdump_prefs")

class PreferencesRepository(private val context: Context) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val cacheAdapter = moshi.adapter(CachedLibraryPayload::class.java)

    private object Keys {
        val PROD_KEYS_URI = stringPreferencesKey("prod_keys_uri")
        val PROD_KEYS_NAME = stringPreferencesKey("prod_keys_name")
        val TITLE_KEYS_URI = stringPreferencesKey("title_keys_uri")
        val TITLE_KEYS_NAME = stringPreferencesKey("title_keys_name")
        val GAME_DIR_URI = stringPreferencesKey("game_dir_uri")
        val GAME_DIR_NAME = stringPreferencesKey("game_dir_name")
        val OUTPUT_DIR_URI = stringPreferencesKey("output_dir_uri")
        val OUTPUT_DIR_NAME = stringPreferencesKey("output_dir_name")
        val OUTPUT_DIR_WARNING = stringPreferencesKey("output_dir_warning")
        val LOOSE_FILE_URIS = stringSetPreferencesKey("loose_file_uris")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LIBRARY_CACHE_JSON = stringPreferencesKey("library_cache_v2_json")
    }

    val setupConfigFlow: Flow<SetupConfig> = context.nxDumpDataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name
        val theme = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.DARK)
        SetupConfig(
            prodKeysUri = prefs[Keys.PROD_KEYS_URI],
            prodKeysName = prefs[Keys.PROD_KEYS_NAME],
            titleKeysUri = prefs[Keys.TITLE_KEYS_URI],
            titleKeysName = prefs[Keys.TITLE_KEYS_NAME],
            gameDirUri = prefs[Keys.GAME_DIR_URI],
            gameDirName = prefs[Keys.GAME_DIR_NAME],
            outputDirUri = prefs[Keys.OUTPUT_DIR_URI],
            outputDirName = prefs[Keys.OUTPUT_DIR_NAME],
            outputDirRestrictedWarning = prefs[Keys.OUTPUT_DIR_WARNING],
            looseFileUris = prefs[Keys.LOOSE_FILE_URIS]?.toList()?.sorted() ?: emptyList(),
            themeMode = theme,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false
        )
    }

    val cachedLibraryFlow: Flow<CachedLibraryPayload> = context.nxDumpDataStore.data.map { prefs ->
        val raw = prefs[Keys.LIBRARY_CACHE_JSON]
        if (raw.isNullOrBlank()) {
            CachedLibraryPayload()
        } else {
            runCatching { cacheAdapter.fromJson(raw) }.getOrNull() ?: CachedLibraryPayload()
        }
    }

    suspend fun setProdKeys(uri: String, displayName: String) {
        context.nxDumpDataStore.edit {
            it[Keys.PROD_KEYS_URI] = uri
            it[Keys.PROD_KEYS_NAME] = displayName
        }
    }

    suspend fun setTitleKeys(uri: String?, displayName: String?) {
        context.nxDumpDataStore.edit {
            if (uri == null) {
                it.remove(Keys.TITLE_KEYS_URI)
                it.remove(Keys.TITLE_KEYS_NAME)
            } else {
                it[Keys.TITLE_KEYS_URI] = uri
                it[Keys.TITLE_KEYS_NAME] = displayName ?: "title.keys"
            }
        }
    }

    suspend fun setGameDir(uri: String, displayName: String) {
        context.nxDumpDataStore.edit {
            it[Keys.GAME_DIR_URI] = uri
            it[Keys.GAME_DIR_NAME] = displayName
        }
    }

    suspend fun setOutputDir(uri: String, displayName: String, warning: String?) {
        context.nxDumpDataStore.edit {
            it[Keys.OUTPUT_DIR_URI] = uri
            it[Keys.OUTPUT_DIR_NAME] = displayName
            if (warning == null) it.remove(Keys.OUTPUT_DIR_WARNING)
            else it[Keys.OUTPUT_DIR_WARNING] = warning
        }
    }

    suspend fun addLooseFileUri(uri: String) {
        context.nxDumpDataStore.edit { prefs ->
            val current = prefs[Keys.LOOSE_FILE_URIS] ?: emptySet()
            prefs[Keys.LOOSE_FILE_URIS] = current + uri
        }
    }

    suspend fun removeLooseFileUri(uri: String) {
        context.nxDumpDataStore.edit { prefs ->
            val current = prefs[Keys.LOOSE_FILE_URIS] ?: emptySet()
            prefs[Keys.LOOSE_FILE_URIS] = current - uri
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.nxDumpDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.nxDumpDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun saveCachedLibrary(payload: CachedLibraryPayload) {
        val json = cacheAdapter.toJson(payload)
        context.nxDumpDataStore.edit { it[Keys.LIBRARY_CACHE_JSON] = json }
    }

    suspend fun clearCache() {
        context.nxDumpDataStore.edit { it.remove(Keys.LIBRARY_CACHE_JSON) }
    }
}
