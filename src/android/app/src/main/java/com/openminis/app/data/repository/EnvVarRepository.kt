package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * Manages environment variables with encrypted value storage.
 * Metadata (key names, IDs) stored in JSON file.
 * Values stored in EncryptedSharedPreferences (AES256-GCM).
 * Mirrors iOS EnvVarStore.
 */
class EnvVarRepository(private val context: Context) {

    companion object {
        private const val TAG = "EnvVarRepository"
        private const val METADATA_FILE = "env-vars.json"
        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        /**
         * Optional human-readable description of what this variable is for.
         * Empty when omitted. Stored in the JSON metadata file (not secret),
         * mirrors iOS EnvVarEntry.note.
         */
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    private val cacheLock = Any()

    @Volatile
    private var cachedDict: Map<String, String>? = null

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: self-healing wrapper.
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
    }

    private val metadataFile: File
        get() = File(context.filesDir, METADATA_FILE)

    init {
        loadMetadata()
    }

    // -- Validation --

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    // Keep printable ASCII (0x20-0x7E) and tab (0x09); iOS paste can inject
    // invisible control scalars (e.g. \u009B) that break shell env injection.
    private fun sanitizeValue(value: String): String =
        value.filter { ch ->
            val c = ch.code
            (c in 0x20..0x7E) || c == 0x09
        }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && it.id != excludeId }

    // -- CRUD --

    fun add(key: String, value: String, note: String = ""): Boolean {
        val normalizedKey = key.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        val sanitized = sanitizeValue(value)

        synchronized(cacheLock) {
            if (isDuplicateKey(normalizedKey)) return false

            val entry = EnvVarEntry(key = normalizedKey, note = note.trim())
            _entries.value = _entries.value + entry
            encryptedPrefs.edit().putString(normalizedKey, sanitized).apply()
            cachedDict = cachedDict?.let { existing ->
                Collections.unmodifiableMap(LinkedHashMap(existing).apply { put(normalizedKey, sanitized) })
            }
            saveMetadata()
        }
        Log.i(TAG, "Added env var: $normalizedKey")
        return true
    }

    fun update(id: String, newKey: String, newValue: String, newNote: String = ""): Boolean {
        val normalizedKey = newKey.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        val sanitized = sanitizeValue(newValue)
        val oldKey: String

        synchronized(cacheLock) {
            val current = _entries.value.find { it.id == id } ?: return false

            // Check for duplicate (excluding self)
            if (isDuplicateKey(normalizedKey, excludeId = id)) return false
            oldKey = current.key

            // Delete old Keychain entry if key changed
            if (current.key != normalizedKey) {
                encryptedPrefs.edit().remove(current.key).apply()
            }

            // Update metadata
            _entries.value = _entries.value.map {
                if (it.id == id) it.copy(key = normalizedKey, note = newNote.trim()) else it
            }

            // Save new value
            encryptedPrefs.edit().putString(normalizedKey, sanitized).apply()
            cachedDict = cachedDict?.let { existing ->
                Collections.unmodifiableMap(
                    LinkedHashMap(existing).apply {
                        if (current.key != normalizedKey) remove(current.key)
                        put(normalizedKey, sanitized)
                    },
                )
            }
            saveMetadata()
        }
        Log.i(TAG, "Updated env var: $oldKey → $normalizedKey")
        return true
    }

    fun delete(id: String) {
        val deletedKey: String
        synchronized(cacheLock) {
            val entry = _entries.value.find { it.id == id } ?: return
            deletedKey = entry.key
            encryptedPrefs.edit().remove(entry.key).apply()
            _entries.value = _entries.value.filter { it.id != id }
            cachedDict = cachedDict?.let { existing ->
                Collections.unmodifiableMap(LinkedHashMap(existing).apply { remove(entry.key) })
            }
            saveMetadata()
        }
        Log.i(TAG, "Deleted env var: $deletedKey")
    }

    fun getValue(key: String): String? {
        val dict = ensureCache()
        dict[key]?.let { return it }
        return if (_entries.value.any { it.key == key }) null else encryptedPrefs.getString(key, null)
    }

    /**
     * Returns all env vars as an immutable Map<String, String> for sandbox injection.
     * Served from an in-memory cache kept in sync with [add]/[update]/[delete] to avoid
     * decrypting EncryptedSharedPreferences on every shell command.
     */
    fun allAsDict(): Map<String, String> = ensureCache()

    private fun ensureCache(): Map<String, String> {
        cachedDict?.let { return it }
        synchronized(cacheLock) {
            cachedDict?.let { return it }
            val result = LinkedHashMap<String, String>()
            for (entry in _entries.value) {
                val value = encryptedPrefs.getString(entry.key, null)
                if (value != null) {
                    result[entry.key] = value
                }
            }
            val immutable = Collections.unmodifiableMap(result)
            cachedDict = immutable
            return immutable
        }
    }

    // -- Metadata Persistence --

    private fun saveMetadata() {
        try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("key", entry.key)
                if (entry.note.isNotEmpty()) obj.put("note", entry.note)
                obj.put("createdAt", entry.createdAt)
                array.put(obj)
            }
            metadataFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }

    private fun loadMetadata() {
        try {
            if (!metadataFile.exists()) return
            val array = JSONArray(metadataFile.readText())
            val entries = mutableListOf<EnvVarEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                entries.add(EnvVarEntry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    key = obj.optString("key", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0),
                ))
            }
            _entries.value = entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata: ${e.message}")
        }
    }
}
