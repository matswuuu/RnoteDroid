package dev.matswuuu.rnote.ui.viewer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.matswuuu.rnote.R
import dev.matswuuu.rnote.data.RecentFilesRepository
import dev.matswuuu.rnote.format.RnoteFormatException
import dev.matswuuu.rnote.format.RnoteParser
import dev.matswuuu.rnote.render.DocumentScene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.coroutines.coroutineContext

sealed interface ViewerState {
    data object Loading : ViewerState
    data class Error(val message: String) : ViewerState
    data class Ready(val scene: DocumentScene) : ViewerState
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private var loadedUri: Uri? = null
    private var job: Job? = null

    /** The viewport of the document view per scene, kept across configuration changes. */
    private var savedViewport: Pair<DocumentScene, DocumentView.SavedViewport>? = null

    /** Whether the warning about elements that can't be displayed was shown for the current document. */
    var failedStrokesWarningShown = false

    fun savedViewportFor(scene: DocumentScene): DocumentView.SavedViewport? =
        savedViewport?.takeIf { it.first === scene }?.second

    /** Remembers the viewport, unless [scene] is no longer the current document. */
    fun saveViewport(scene: DocumentScene, viewport: DocumentView.SavedViewport) {
        if ((_state.value as? ViewerState.Ready)?.scene === scene) savedViewport = scene to viewport
    }

    fun load(uri: Uri) {
        if (uri == loadedUri) return
        job?.cancel()
        loadedUri = uri
        savedViewport = null
        failedStrokesWarningShown = false
        _title.value = ""
        _state.value = ViewerState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                val scene = withContext(Dispatchers.Default) { loadScene(uri) }
                launch(Dispatchers.Default) { saveThumbnail(uri, scene) }
                ViewerState.Ready(scene)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open $uri", e)
                // the access permission of a recent file is gone; it can't be opened from the list anymore
                if (e is SecurityException) RecentFilesRepository.get(getApplication()).remove(uri)
                ViewerState.Error(errorMessage(e))
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Out of memory while opening $uri", e)
                ViewerState.Error(getApplication<Application>().getString(R.string.error_out_of_memory))
            }
        }
    }

    /** Forgets the current document, freeing its memory. */
    fun close() {
        job?.cancel()
        job = null
        loadedUri = null
        savedViewport = null
        _state.value = ViewerState.Loading
    }

    fun retry() {
        val uri = loadedUri ?: return
        loadedUri = null
        load(uri)
    }

    private suspend fun loadScene(uri: Uri): DocumentScene {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
        _title.value = name.removeSuffix(".rnote")

        val file = withContext(Dispatchers.IO) {
            val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            stream.use { RnoteParser.parse(it) }
        }
        coroutineContext.ensureActive()
        val scene = DocumentScene.create(file)
        coroutineContext.ensureActive()
        RecentFilesRepository.get(app).add(uri, name)
        return scene
    }

    private fun saveThumbnail(uri: Uri, scene: DocumentScene) {
        try {
            scene.renderThumbnail(THUMBNAIL_SIZE)?.let {
                RecentFilesRepository.get(getApplication()).thumbnails.save(uri, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create thumbnail", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory while creating thumbnail", e)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun errorMessage(e: Exception): String {
        val app = getApplication<Application>()
        return when (e) {
            is RnoteFormatException -> e.message ?: app.getString(R.string.error_invalid_file)
            is FileNotFoundException -> app.getString(R.string.error_not_found)
            is SecurityException -> app.getString(R.string.error_permission)
            else -> app.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "ViewerViewModel"
        private const val THUMBNAIL_SIZE = 384
    }
}
