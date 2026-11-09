package dev.matswuuu.rnote.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.matswuuu.rnote.R
import dev.matswuuu.rnote.format.Layout
import dev.matswuuu.rnote.render.DocumentScene
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    var documentView by remember { mutableStateOf<DocumentView?>(null) }
    val readyScene = (state as? ViewerState.Ready)?.scene
    var viewport by remember(readyScene) { mutableStateOf<DocumentView.ViewportInfo?>(null) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showGoToPage by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val ready = state as? ViewerState.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = { documentView?.fitWidth() }) {
                            Icon(painterResource(R.drawable.ic_fit_width), contentDescription = stringResource(R.string.fit_width))
                        }
                        IconButton(onClick = { documentView?.fitPage() }) {
                            Icon(painterResource(R.drawable.ic_fit_page), contentDescription = stringResource(R.string.fit_page))
                        }
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        val export = rememberExportLauncher(
                            scene = ready.scene,
                            baseName = title.ifBlank { stringResource(R.string.export_untitled) },
                            onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                        )
                        IconButton(onClick = { export.launch() }) {
                            Icon(painterResource(R.drawable.ic_export), contentDescription = stringResource(R.string.export))
                        }
                        IconButton(onClick = { showInfo = true }) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.document_info))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = state) {
                ViewerState.Loading -> LoadingContent()
                is ViewerState.Error -> ErrorContent(s.message, onRetry = viewModel::retry, onBack = onBack)
                is ViewerState.Ready -> {
                    DocumentViewHost(
                        scene = s.scene,
                        viewModel = viewModel,
                        onViewCreated = { documentView = it },
                        onViewportChanged = { viewport = it },
                    )
                    viewport?.let { info ->
                        ViewportOverlay(
                            info = info,
                            onPageClick = { showGoToPage = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }

    if (ready != null) {
        val failedMessage = stringResource(R.string.failed_strokes_warning, ready.scene.failedStrokes)
        LaunchedEffect(ready.scene) {
            if (ready.scene.failedStrokes > 0 && !viewModel.failedStrokesWarningShown) {
                viewModel.failedStrokesWarningShown = true
                snackbarHostState.showSnackbar(failedMessage)
            }
        }
        if (showInfo) {
            DocumentInfoDialog(title = title, scene = ready.scene, onDismiss = { showInfo = false })
        }
        if (showGoToPage) {
            GoToPageDialog(
                pageCount = ready.scene.pages.size,
                onGo = { page ->
                    showGoToPage = false
                    documentView?.goToPage(page)
                },
                onDismiss = { showGoToPage = false },
            )
        }
    }
}

@Composable
private fun DocumentViewHost(
    scene: DocumentScene,
    viewModel: ViewerViewModel,
    onViewCreated: (DocumentView?) -> Unit,
    onViewportChanged: (DocumentView.ViewportInfo) -> Unit,
) {
    val desk = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerHighest
    val deskArgb = desk.toArgb()
    val context = LocalContext.current
    val view = remember(context) {
        DocumentView(context).apply { pendingViewport = viewModel.savedViewportFor(scene) }
    }

    DisposableEffect(view) {
        onViewCreated(view)
        onDispose {
            view.saveViewport()?.let { viewModel.saveViewport(scene, it) }
            onViewCreated(null)
            view.release()
        }
    }

    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize(),
        update = {
            it.deskColor = deskArgb
            it.onViewportChanged = onViewportChanged
            it.setScene(scene)
        },
    )
}

@Composable
private fun ViewportOverlay(
    info: DocumentView.ViewportInfo,
    onPageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        tonalElevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.page >= 0 && info.pageCount > 0) {
                Text(
                    text = stringResource(R.string.page_indicator, info.page + 1, info.pageCount),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable(enabled = info.pageCount > 1, onClick = onPageClick)
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                )
                Text("·", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = stringResource(R.string.zoom_percent, (info.zoom * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(
                    start = if (info.page >= 0) 8.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp
                ),
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_document),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun DocumentInfoDialog(title: String, scene: DocumentScene, onDismiss: () -> Unit) {
    val doc = scene.document
    val format = doc.format
    val layout = when (doc.layout) {
        Layout.FIXED_SIZE -> R.string.layout_fixed_size
        Layout.CONTINUOUS_VERTICAL -> R.string.layout_continuous_vertical
        Layout.SEMI_INFINITE -> R.string.layout_semi_infinite
        Layout.INFINITE -> R.string.layout_infinite
    }
    val mmPerUnit = if (format.dpi > 0) 25.4 / format.dpi else 25.4 / 96.0

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.document_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(stringResource(R.string.info_file), title)
                InfoRow(stringResource(R.string.info_version), stringResource(R.string.info_version_value, scene.file.version))
                InfoRow(stringResource(R.string.info_layout), stringResource(layout))
                if (scene.pages.isNotEmpty()) {
                    InfoRow(stringResource(R.string.info_pages), scene.pages.size.toString())
                }
                InfoRow(
                    stringResource(R.string.info_page_size),
                    stringResource(R.string.info_page_size_value, format.width * mmPerUnit, format.height * mmPerUnit),
                )
                InfoRow(stringResource(R.string.info_elements), scene.strokeCount.toString())
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
                label = { Text(stringResource(R.string.page_number_hint, pageCount)) },
                singleLine = true,
                isError = text.isNotEmpty() && page == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let { onGo(it - 1) } }),
            )
        },
        confirmButton = {
            TextButton(onClick = { page?.let { onGo(it - 1) } }, enabled = page != null) {
                Text(stringResource(R.string.go))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
