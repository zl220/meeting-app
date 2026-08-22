package com.meetingapp.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEY_API_KEY = stringPreferencesKey("openai_api_key")
    private val KEY_AI_WAKE_NAME = stringPreferencesKey("ai_wake_name")
    private val KEY_PREFERRED_LANG = stringPreferencesKey("preferred_language")

    val apiKey: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }
    val aiWakeName: Flow<String> = context.dataStore.data.map { it[KEY_AI_WAKE_NAME] ?: "小谈" }
    val preferredLanguage: Flow<String> = context.dataStore.data.map { it[KEY_PREFERRED_LANG] ?: "zh" }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[KEY_API_KEY] = key }
    }

    suspend fun setAiWakeName(name: String) {
        context.dataStore.edit { it[KEY_AI_WAKE_NAME] = name }
    }

    suspend fun setPreferredLanguage(lang: String) {
        context.dataStore.edit { it[KEY_PREFERRED_LANG] = lang }
    }
}
