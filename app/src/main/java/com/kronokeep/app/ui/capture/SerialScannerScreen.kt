package com.kronokeep.app.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kronokeep.app.data.capture.codeKind
import com.kronokeep.app.data.capture.toOcrLines
import com.kronokeep.app.domain.capture.CandidateSource
import com.kronokeep.app.domain.capture.CandidateTracker
import com.kronokeep.app.domain.capture.OcrLine
import com.kronokeep.app.domain.capture.ReceiptLayout
import com.kronokeep.app.domain.capture.SerialCandidate
import com.kronokeep.app.domain.capture.SerialCandidates

/** Aim box as a fraction of the preview. Only text inside it is read, which keeps labels and logos out. */
private const val AIM_WIDTH = 0.86f
private const val AIM_HEIGHT = 0.26f

/**
 * Full-screen live scanner for serial stickers, barcodes, VINs and (for vehicles) plates. Barcodes are
 * offered after one read; OCR text only once it's stable across frames ([CandidateTracker]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerialScannerScreen(
    vehicle: Boolean,
    onPicked: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    // Picks up a grant made in system settings while we were in the background.
    LifecycleResumeEffect(Unit) {
        granted = hasPermission()
        onPauseOrDispose { }
    }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vehicle) "Scan plate, VIN or serial" else "Scan serial number") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close scanner") }
                },
                actions = {
                    if (granted) {
                        IconButton(onClick = { torchOn = !torchOn }) {
                            Icon(
                                if (torchOn) Icons.Outlined.FlashlightOff else Icons.Outlined.FlashlightOn,
                                contentDescription = if (torchOn) "Turn off light" else "Turn on light",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (granted) {
                LiveScanner(vehicle = vehicle, torchOn = torchOn, onPicked = onPicked)
            } else {
                PermissionNeeded(
                    denied = asked,
                    onRequest = { permission.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                        )
                    },
                    onTypeInstead = onClose,
                )
            }
        }
    }
}

@Composable
private fun LiveScanner(vehicle: Boolean, torchOn: Boolean, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var candidates by remember { mutableStateOf(emptyList<SerialCandidate>()) }
    val tracker = remember { CandidateTracker() }
    val controller = remember {
        LifecycleCameraController(context).apply { setEnabledUseCases(CameraController.IMAGE_ANALYSIS) }
    }

    DisposableEffect(lifecycleOwner, controller) {
        val barcodes = BarcodeScanning.getClient()
        val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val main = ContextCompat.getMainExecutor(context)
        // View-referenced coordinates map boxes onto the PreviewView, so the aim box can filter text.
        controller.setImageAnalysisAnalyzer(
            main,
            MlKitAnalyzer(listOf(barcodes, text), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, main) { result ->
                val frame = buildList {
                    result.getValue(barcodes).orEmpty().forEach { code ->
                        val kind = code.codeKind() ?: return@forEach
                        val raw = code.rawValue ?: return@forEach
                        SerialCandidates.fromBarcode(raw, kind)?.let(::add)
                    }
                    result.getValue(text)?.let { recognized ->
                        val aimed = recognized.toOcrLines().filter { it.inAim(viewSize) }
                        addAll(SerialCandidates.fromText(ReceiptLayout.rows(aimed), vehicle))
                    }
                }
                candidates = tracker.observe(frame)
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            barcodes.close()
            text.close()
        }
    }
    LaunchedEffect(torchOn) { controller.enableTorch(torchOn) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewSize = it }) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            AimOverlay(Modifier.fillMaxSize())
            Text(
                "Fit the serial sticker or barcode inside the box",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            )
        }
        CandidatePanel(candidates, onPicked)
    }
}

private fun OcrLine.inAim(view: IntSize): Boolean {
    if (view == IntSize.Zero) return true
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    val halfW = view.width * AIM_WIDTH / 2
    val halfH = view.height * AIM_HEIGHT / 2
    return cx in (view.width / 2f - halfW)..(view.width / 2f + halfW) &&
        cy in (view.height / 2f - halfH)..(view.height / 2f + halfH)
}

@Composable
private fun AimOverlay(modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier) {
        val aim = Size(size.width * AIM_WIDTH, size.height * AIM_HEIGHT)
        val topLeft = Offset((size.width - aim.width) / 2, (size.height - aim.height) / 2)
        val radius = CornerRadius(16.dp.toPx())
        val hole = Path().apply {
            addRoundRect(RoundRect(topLeft.x, topLeft.y, topLeft.x + aim.width, topLeft.y + aim.height, radius))
        }
        clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.55f)) }
        drawRoundRect(accent, topLeft, aim, radius, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun CandidatePanel(candidates: List<SerialCandidate>, onPicked: (String) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)) {
            if (candidates.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Looking for a serial number…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    "Tap the right one",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                candidates.forEach { candidate ->
                    ListItem(
                        headlineContent = { Text(candidate.value, fontFamily = FontFamily.Monospace) },
                        supportingContent = { Text(candidate.source.label) },
                        trailingContent = { TextButton(onClick = { onPicked(candidate.value) }) { Text("Use") } },
                        modifier = Modifier.clickable { onPicked(candidate.value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionNeeded(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit, onTypeInstead: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.NoPhotography, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Camera access needed", style = MaterialTheme.typography.titleMedium)
        Text(
            "The scanner reads serial stickers and barcodes live, on your phone. Nothing is recorded or uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequest) { Text("Allow camera") }
        if (denied) OutlinedButton(onClick = onOpenSettings) { Text("Open settings") }
        TextButton(onClick = onTypeInstead) { Text("Type it instead") }
    }
}

private val CandidateSource.label: String
    get() = when (this) {
        CandidateSource.LABELLED_TEXT -> "Next to a serial label"
        CandidateSource.BARCODE -> "Barcode"
        CandidateSource.VIN -> "Chassis / VIN"
        CandidateSource.PLATE -> "Plate number"
        CandidateSource.PRODUCT_CODE -> "Product barcode · usually not the serial"
    }
