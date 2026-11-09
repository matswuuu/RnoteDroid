package dev.matswuuu.rnote.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.matswuuu.rnote.ui.home.HomeScreen
import dev.matswuuu.rnote.ui.viewer.ViewerScreen
import dev.matswuuu.rnote.ui.viewer.ViewerViewModel

private val OPEN_DOCUMENT_MIME_TYPES = arrayOf(
    "application/rnote",
    "application/x-rnote",
    "application/gzip",
    "application/x-gzip",
    "application/octet-stream",
    "*/*",
)

/**
 * @param externalUri a document to open, e.g. from a "view" intent of another app.
 * @param onExternalUriConsumed called once [externalUri] has been opened.
 */
@Composable
fun RnoteApp(externalUri: Uri?, onExternalUriConsumed: () -> Unit) {
    val context = LocalContext.current
    val viewerViewModel: ViewerViewModel = viewModel()
    var openUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    LaunchedEffect(externalUri) {
        if (externalUri != null) {
            // keeps the file accessible from the recent files, if the sending app allows it
            try {
                context.contentResolver.takePersistableUriPermission(externalUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            openUri = externalUri
            onExternalUriConsumed()
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // not all providers support persistable permissions; the file then can only be opened now
            }
            openUri = uri
        }
    }

    AnimatedContent(
        targetState = openUri,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { uri ->
        if (uri == null) {
            HomeScreen(
                onOpenFile = { openDocument.launch(OPEN_DOCUMENT_MIME_TYPES) },
                onOpenRecent = { openUri = it },
            )
        } else {
            LaunchedEffect(uri) { viewerViewModel.load(uri) }
            BackHandler {
                openUri = null
                viewerViewModel.close()
            }
            ViewerScreen(
                viewModel = viewerViewModel,
                onBack = {
                    openUri = null
                    viewerViewModel.close()
                },
            )
        }
    }
}
