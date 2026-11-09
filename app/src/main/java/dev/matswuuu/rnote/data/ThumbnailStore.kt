package dev.matswuuu.rnote.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest

/** Caches small preview images of documents. */
class ThumbnailStore(context: Context) {
    private val dir = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    private val _version = MutableStateFlow(0)

    /** Changes whenever a thumbnail was written, so that the UI can reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun fileFor(uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".png")
    }

    fun load(uri: Uri): Bitmap? = fileFor(uri).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun save(uri: Uri, bitmap: Bitmap) {
        try {
            val file = fileFor(uri)
            val tmp = File(dir, file.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
            _version.update { it + 1 }
        } catch (_: Exception) {
            // thumbnails are optional
        }
    }

    fun delete(uri: Uri) {
        fileFor(uri).delete()
    }
}
