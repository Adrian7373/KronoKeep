package com.kronokeep.app.ui.assets.detail

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.kronokeep.app.domain.model.Document
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.label
import com.kronokeep.app.ui.theme.KronoTheme
import kotlinx.coroutines.launch

@Composable
fun DocumentRow(document: Document, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = {
            Icon(
                if (document.isImage) Icons.Outlined.Image else Icons.Outlined.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(document.kind.label) },
        supportingContent = {
            Column {
                Text("${Formats.date(document.createdAt)} · ${Formats.bytes(document.sizeBytes)}")
                UploadStatus(document)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${document.kind.label}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun UploadStatus(document: Document) {
    val (icon, text, color) = when {
        document.syncState == SyncState.FAILED && !document.isUploaded ->
            Triple(Icons.Outlined.ErrorOutline, "Upload failed: file missing", KronoTheme.status.overdue)
        !document.isUploaded ->
            Triple(Icons.Outlined.CloudUpload, "On this phone · uploads when online", MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(Icons.Outlined.CloudDone, "In your private vault", KronoTheme.status.ok)
    }
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

class DocumentPickers(
    val takePhoto: () -> Unit,
    val pickPhoto: () -> Unit,
    val pickFile: () -> Unit,
)

/** Camera (system app via FileProvider, no CAMERA permission), photo picker, and the document picker for PDFs. */
@Composable
fun rememberDocumentPickers(
    kind: () -> DocumentKind,
    newCaptureUri: () -> String,
    onCaptureCancelled: (String) -> Unit,
    onPicked: (uri: String, mimeType: String?, kind: DocumentKind, isCapture: Boolean) -> Unit,
    onError: (String) -> Unit,
): DocumentPickers {
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture ?: return@rememberLauncherForActivityResult
        pendingCapture = null
        if (saved) onPicked(uri, "image/jpeg", kind(), true) else onCaptureCancelled(uri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onPicked(it.toString(), null, kind(), false) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onPicked(it.toString(), null, kind(), false) }
    }

    return DocumentPickers(
        takePhoto = {
            val uri = newCaptureUri()
            pendingCapture = uri
            try {
                takePicture.launch(uri.toUri())
            } catch (_: ActivityNotFoundException) {
                pendingCapture = null
                onCaptureCancelled(uri)
                onError("No camera app found on this device")
            }
        },
        pickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        pickFile = { pickFile.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/heic", "image/heif")) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDocumentSheet(
    kind: DocumentKind,
    onKindChange: (DocumentKind) -> Unit,
    pickers: DocumentPickers,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "What is it?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            FlowRow(
                Modifier.padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DocumentKind.entries.forEach { k ->
                    FilterChip(selected = k == kind, onClick = { onKindChange(k) }, label = { Text(k.label) })
                }
            }
            ListItem(
                headlineContent = { Text("Take a photo") },
                leadingContent = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                modifier = Modifier.clickable { pick(pickers.takePhoto) },
            )
            ListItem(
                headlineContent = { Text("Choose from photos") },
                leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                modifier = Modifier.clickable { pick(pickers.pickPhoto) },
            )
            ListItem(
                headlineContent = { Text("Choose a file") },
                supportingContent = { Text("PDF, JPEG, PNG or HEIC, up to 15 MB") },
                leadingContent = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                modifier = Modifier.clickable { pick(pickers.pickFile) },
            )
        }
    }
}
