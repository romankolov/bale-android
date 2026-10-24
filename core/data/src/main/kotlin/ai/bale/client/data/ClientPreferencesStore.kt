package ai.bale.client.data

import ai.bale.client.domain.AppLanguage
import ai.bale.client.domain.AppThemeMode
import ai.bale.client.domain.ClientPreferences
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Per-account dialog flags that do not belong in the message schema (drafts, mute, unread, pin). */
internal data class AccountUiOverlay(
    val drafts: Map<String, String> = emptyMap(),
    val pinnedPeers: Set<String> = emptySet(),
    val archivedPeers: Set<String> = emptySet(),
    val mutedPeers: Set<String> = emptySet(),
    val markedUnreadPeers: Set<String> = emptySet(),
    val unreadMentions: Map<String, Int> = emptyMap(),
)

/**
 * Reactive appearance + per-account dialog state store. Persisted through [SharedPreferences] in
 * production and an in-memory map in JVM tests, deliberately keeping Room at schema v3 so an
 * upgrade over an existing install never needs a destructive migration.
 */
internal class ClientPreferencesStore(private val prefs: SharedPreferences? = null) {
    private val memory = ConcurrentHashMap<String, String>()
    private val mutablePreferences = MutableStateFlow(readPreferences())
    val preferences: StateFlow<ClientPreferences> = mutablePreferences.asStateFlow()

    private val mutableOverlays = MutableStateFlow<Map<Int, AccountUiOverlay>>(emptyMap())
    val overlays: StateFlow<Map<Int, AccountUiOverlay>> = mutableOverlays.asStateFlow()

    private fun read(key: String): String? = prefs?.getString(key, null) ?: memory[key]

    private fun write(key: String, value: String) {
        val editor = prefs?.edit()
        if (editor != null) editor.putString(key, value).apply() else memory[key] = value
    }

    private fun remove(key: String) {
        val editor = prefs?.edit()
        if (editor != null) editor.remove(key).apply() else memory.remove(key)
    }

    private fun readPreferences(): ClientPreferences {
        val theme = runCatching { AppThemeMode.valueOf(read("theme_mode") ?: AppThemeMode.System.name) }
            .getOrDefault(AppThemeMode.System)
        val language = runCatching { AppLanguage.valueOf(read("app_language") ?: AppLanguage.System.name) }
            .getOrDefault(AppLanguage.System)
        return ClientPreferences(themeMode = theme, dynamicColor = read("dynamic_color") != "false", language = language)
    }

    fun updatePreferences(transform: (ClientPreferences) -> ClientPreferences) {
        mutablePreferences.update { current ->
            val next = transform(current)
            write("theme_mode", next.themeMode.name)
            write("dynamic_color", next.dynamicColor.toString())
            write("app_language", next.language.name)
            next
        }
    }

    fun overlayFor(accountId: Int): AccountUiOverlay {
        mutableOverlays.value[accountId]?.let { return it }
        val loaded = readAccountOverlay(accountId)
        mutableOverlays.update { all -> if (accountId in all) all else all + (accountId to loaded) }
        return mutableOverlays.value[accountId] ?: loaded
    }

    fun updateOverlay(accountId: Int, transform: (AccountUiOverlay) -> AccountUiOverlay) {
        mutableOverlays.update { all ->
            val updated = transform(all[accountId] ?: readAccountOverlay(accountId))
            writeAccountOverlay(accountId, updated)
            all + (accountId to updated)
        }
    }

    fun clearAccount(accountId: Int) {
        mutableOverlays.update { it - accountId }
        remove("overlay_$accountId")
    }

    private fun readAccountOverlay(accountId: Int): AccountUiOverlay {
        val raw = read("overlay_$accountId") ?: return AccountUiOverlay()
        return runCatching {
            val obj = JSONObject(raw)
            AccountUiOverlay(
                drafts = jsonMap(obj.optJSONObject("drafts")).mapValues { it.value.toString() },
                unreadMentions = jsonMap(obj.optJSONObject("mentions")).mapValues { it.value.toString().toIntOrNull() ?: 0 }
                    .filterValues { it > 0 },
                pinnedPeers = jsonSet(obj.optJSONArray("pinned")),
                archivedPeers = jsonSet(obj.optJSONArray("archived")),
                mutedPeers = jsonSet(obj.optJSONArray("muted")),
                markedUnreadPeers = jsonSet(obj.optJSONArray("markedUnread")),
            )
        }.getOrDefault(AccountUiOverlay())
    }

    private fun writeAccountOverlay(accountId: Int, overlay: AccountUiOverlay) {
        runCatching {
            val obj = JSONObject()
                .put("drafts", JSONObject(overlay.drafts as Map<*, *>))
                .put("mentions", JSONObject(overlay.unreadMentions as Map<*, *>))
                .put("pinned", JSONArray(overlay.pinnedPeers.toList()))
                .put("archived", JSONArray(overlay.archivedPeers.toList()))
                .put("muted", JSONArray(overlay.mutedPeers.toList()))
                .put("markedUnread", JSONArray(overlay.markedUnreadPeers.toList()))
            write("overlay_$accountId", obj.toString())
        }
    }

    private fun jsonMap(obj: JSONObject?): Map<String, Any> {
        if (obj == null) return emptyMap()
        return buildMap { obj.keys().forEach { put(it, obj.opt(it) ?: "") } }
    }

    private fun jsonSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        return buildSet {
            for (index in 0 until arr.length()) {
                val value = arr.optString(index, "")
                if (value.isNotBlank()) add(value)
            }
        }
    }
}
