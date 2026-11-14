package com.kronokeep.app.ui.documents

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(viewModel: DocumentViewerViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = when (val s = state) {
        is ViewerUiState.Image -> s.kind.label
        is ViewerUiState.Pdf -> s.kind.label
        else -> "Document"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ViewerUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ViewerUiState.Error -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = "Can't open this document",
                    body = s.message,
                    action = { Button(onClick = viewModel::load) { Text("Try again") } },
                )
                is ViewerUiState.Image -> ZoomableImage(s.model)
                is ViewerUiState.Pdf -> PdfPages(s.path)
            }
        }
    }
}

@Composable
private fun ZoomableImage(model: Any) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    // Both caches off: document images must not persist anywhere beyond this screen.
    val request = remember(model) {
        ImageRequest.Builder(context)
            .data(model)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = "Document image",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .transformable(transform)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

/** Renders each PDF page to a bitmap at screen width. PdfRenderer allows one open page at a time, hence the mutex. */
private class PdfDocumentHandle(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    suspend fun aspectRatio(index: Int): Float = lock.withLock {
        withContext(Dispatchers.IO) { renderer.openPage(index).use { it.width.toFloat() / it.height } }
    }

    suspend fun render(index: Int, widthPx: Int): ImageBitmap? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }

    fun close() {
        // Wait for any in-flight render before closing the native renderer.
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                closed = true
                renderer.close()
                fd.close()
            }
        }
    }
}

@Composable
private fun PdfPages(path: String) {
    val handle by produceState<PdfDocumentHandle?>(null, path) {
        val opened = withContext(Dispatchers.IO) { runCatching { PdfDocumentHandle(File(path)) }.getOrNull() }
        value = opened
        awaitDispose { opened?.close() }
    }
    val doc = handle ?: return Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val widthPx = constraints.maxWidth
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(doc.pageCount) { index ->
                val ratio by produceState(1f / 1.414f, doc, index) { value = doc.aspectRatio(index) }
                val page by produceState<ImageBitmap?>(null, doc, index, widthPx) { value = doc.render(index, widthPx) }
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White)) {
                    page?.let {
                        Image(it, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize())
                    } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
