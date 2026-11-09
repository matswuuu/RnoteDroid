package dev.matswuuu.rnote.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: Uri,
    val name: String,
    val lastOpened: Long,
)

/** Remembers recently opened documents, most recent first. */
class RecentFilesRepository private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)
    private val _files = MutableStateFlow(load())
    val files: StateFlow<List<RecentFile>> = _files.asStateFlow()

    val thumbnails = ThumbnailStore(context)

    @Synchronized
    fun add(uri: Uri, name: String) {
        val updated = listOf(RecentFile(uri, name, System.currentTimeMillis())) +
            _files.value.filter { it.uri != uri }
        val kept = updated.take(MAX_ENTRIES)
        updated.drop(MAX_ENTRIES).forEach { forget(it.uri) }
        save(kept)
    }

    @Synchronized
    fun remove(uri: Uri) {
        save(_files.value.filter { it.uri != uri })
        forget(uri)
    }

    private fun forget(uri: Uri) {
        thumbnails.delete(uri)
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // the permission was not persisted
        }
    }

    private fun save(files: List<RecentFile>) {
        _files.value = files
        val array = JSONArray()
        files.forEach {
            array.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("name", it.name)
                    .put("lastOpened", it.lastOpened)
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private fun load(): List<RecentFile> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RecentFile(Uri.parse(o.getString("uri")), o.getString("name"), o.getLong("lastOpened"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val KEY = "files"
        private const val MAX_ENTRIES = 30

        @Volatile
        private var instance: RecentFilesRepository? = null

        fun get(context: Context): RecentFilesRepository = instance ?: synchronized(this) {
            instance ?: RecentFilesRepository(context.applicationContext).also { instance = it }
        }
    }
}
