package com.ktakjm.fingerlock.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository private constructor(private val context: Context) {

    val lockedApps: Flow<Set<String>> =
        context.dataStore.data.map { it[KEY_LOCKED_APPS] ?: emptySet() }

    val graceSeconds: Flow<Int> =
        context.dataStore.data.map { it[KEY_GRACE_SECONDS] ?: DEFAULT_GRACE_SECONDS }

    val relockOnScreenOff: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_RELOCK_ON_SCREEN_OFF] ?: true }

    val failureThreshold: Flow<Int> =
        context.dataStore.data.map { it[KEY_FAILURE_THRESHOLD] ?: DEFAULT_FAILURE_THRESHOLD }

    // 無断撮影になるため既定OFF。ONにはCAMERA権限が必要(issue #3)
    val intruderPhotoEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_INTRUDER_PHOTO] ?: false }

    suspend fun setLocked(packageName: String, locked: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_LOCKED_APPS] ?: emptySet()
            prefs[KEY_LOCKED_APPS] = if (locked) current + packageName else current - packageName
        }
    }

    suspend fun setGraceSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_GRACE_SECONDS] = seconds }
    }

    suspend fun setRelockOnScreenOff(enabled: Boolean) {
        context.dataStore.edit { it[KEY_RELOCK_ON_SCREEN_OFF] = enabled }
    }

    suspend fun setFailureThreshold(count: Int) {
        context.dataStore.edit { it[KEY_FAILURE_THRESHOLD] = count }
    }

    suspend fun setIntruderPhotoEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INTRUDER_PHOTO] = enabled }
    }

    companion object {
        private val KEY_LOCKED_APPS = stringSetPreferencesKey("locked_apps")
        private val KEY_GRACE_SECONDS = intPreferencesKey("grace_seconds")
        private val KEY_RELOCK_ON_SCREEN_OFF = booleanPreferencesKey("relock_on_screen_off")
        private val KEY_FAILURE_THRESHOLD = intPreferencesKey("failure_threshold")
        private val KEY_INTRUDER_PHOTO = booleanPreferencesKey("intruder_photo_enabled")

        const val DEFAULT_GRACE_SECONDS = 60
        const val DEFAULT_FAILURE_THRESHOLD = 3

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
