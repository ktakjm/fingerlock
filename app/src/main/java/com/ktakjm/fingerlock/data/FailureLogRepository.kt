package com.ktakjm.fingerlock.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FailureEvent(
    val timestamp: Long,
    val packageName: String,
    val failureCount: Int,
    // 将来の写真添付(別issue)用に最初から持たせる
    val photoPath: String? = null,
)

private val Context.failureLogDataStore by preferencesDataStore(name = "failure_log")

class FailureLogRepository private constructor(private val context: Context) {

    // LockActivity破棄後も書き込みを完了させるため、Activityのライフサイクルに依存しない
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    val events: Flow<List<FailureEvent>> =
        context.failureLogDataStore.data.map { prefs ->
            prefs[KEY_EVENTS]?.let { decodeOrEmpty(it) } ?: emptyList()
        }

    fun log(event: FailureEvent) {
        scope.launch {
            context.failureLogDataStore.edit { prefs ->
                val current = prefs[KEY_EVENTS]?.let { decodeOrEmpty(it) } ?: emptyList()
                val updated = (current + event).takeLast(MAX_EVENTS)
                prefs[KEY_EVENTS] = json.encodeToString(updated)
            }
        }
    }

    private fun decodeOrEmpty(raw: String): List<FailureEvent> =
        runCatching { json.decodeFromString<List<FailureEvent>>(raw) }.getOrElse { emptyList() }

    companion object {
        private val KEY_EVENTS = stringPreferencesKey("events")
        private const val MAX_EVENTS = 100

        @Volatile
        private var instance: FailureLogRepository? = null

        fun get(context: Context): FailureLogRepository =
            instance ?: synchronized(this) {
                instance ?: FailureLogRepository(context.applicationContext).also { instance = it }
            }
    }
}
