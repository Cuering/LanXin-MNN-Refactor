package com.lanxin.refactor.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lanxin.refactor.cloud.CloudConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cloudDataStore by preferencesDataStore(name = "cloud_settings")

/**
 * 云端配置持久化（DataStore）。密钥仅存本机，不进 git。
 */
class CloudSettingsStore(private val context: Context) {
    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyApiKey = stringPreferencesKey("api_key")
    private val keyModel = stringPreferencesKey("model")

    val configFlow: Flow<CloudConfig> = context.cloudDataStore.data.map { prefs ->
        CloudConfig(
            baseUrl = prefs[keyBaseUrl] ?: CloudConfig().baseUrl,
            apiKey = prefs[keyApiKey] ?: "",
            model = prefs[keyModel] ?: CloudConfig().model
        )
    }

    suspend fun current(): CloudConfig = configFlow.first()

    suspend fun save(baseUrl: String, apiKey: String, model: String) {
        context.cloudDataStore.edit { prefs ->
            prefs[keyBaseUrl] = baseUrl.trim()
            prefs[keyApiKey] = apiKey.trim()
            prefs[keyModel] = model.trim().ifBlank { "gpt-4o-mini" }
        }
    }
}
