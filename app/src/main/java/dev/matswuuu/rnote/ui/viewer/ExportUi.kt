package dev.matswuuu.rnote.ui.viewer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.matswuuu.rnote.R
import dev.matswuuu.rnote.render.DocumentExporter
import dev.matswuuu.rnote.render.DocumentScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ExportFormat(val mimeType: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    PNG("image/png", "png"),
}

@Composable
internal fun rememberExportLauncher(
    scene: DocumentScene,
    baseName: String,
    onMessage: (String) -> Unit,
): ExportState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showChooser by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<DocumentExporter.Progress?>(null) }

    fun startExport(format: ExportFormat, uri: Uri?) {
        if (uri == null) return
        exporting = DocumentExporter.Progress(0, 1)
        scope.launch(Dispatchers.Default) {
            val result = runCatching {
                val resolver = context.contentResolver
                resolver.openOutputStream(uri)?.use { out ->
                    when (format) {
                        ExportFormat.PDF -> DocumentExporter.writePdf(scene, out) { p -> exporting = p }
                        ExportFormat.PNG -> DocumentExporter.writePng(scene, out) { p -> exporting = p }
                    }
                } ?: error("The destination could not be opened.")
                displayName(context, uri) ?: "$baseName.${format.extension}"
            }
            exporting = null
            withContext(Dispatchers.Main) {
                result.onSuccess { name -> onMessage(context.getString(R.string.export_saved, name)) }
                    .onFailure { e -> onMessage(context.getString(R.string.export_failed, e.message ?: e.toString())) }
            }
        }
    }

    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        startExport(ExportFormat.PDF, uri)
    }
    val createPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        startExport(ExportFormat.PNG, uri)
    }

    if (showChooser) {
        ExportChooserDialog(
            onChoose = { format ->
                showChooser = false
                when (format) {
                    ExportFormat.PDF -> createPdf.launch("$baseName.${format.extension}")
                    ExportFormat.PNG -> createPng.launch("$baseName.${format.extension}")
                }
            },
            onDismiss = { showChooser = false },
        )
    }

    exporting?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.exporting)) },
            text = {
                Column {
                    if (progress.total > 1) {
                        Text(
                            stringResource(R.string.page_indicator, progress.current, progress.total),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { progress.current.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.exporting))
                        }
                    }
                }
            },
        )
    }

    return ExportState(exporting != null) { showChooser = true }
}

internal class ExportState(val busy: Boolean, private val start: () -> Unit) {
    fun launch() = start()
}

@Composable
private fun ExportChooserDialog(onChoose: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(ExportFormat.PDF) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_as)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FormatOption(
                    selected = selected == ExportFormat.PDF,
                    title = stringResource(R.string.export_pdf),
                    description = stringResource(R.string.export_pdf_description),
                    icon = R.drawable.ic_document,
                    onClick = { selected = ExportFormat.PDF },
                )
                FormatOption(
                    selected = selected == ExportFormat.PNG,
                    title = stringResource(R.string.export_png),
                    description = stringResource(R.string.export_png_description),
                    icon = R.drawable.ic_fit_page,
                    onClick = { selected = ExportFormat.PNG },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(selected) }) { Text(stringResource(R.string.export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FormatOption(
    selected: Boolean,
    title: String,
    description: String,
    icon: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
                Spacer(Modifier.width(4.dp))
                Icon(painterResource(icon), contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (_: Exception) {
    null
}
