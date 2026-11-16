package com.kronokeep.app.ui.assets.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kronokeep.app.domain.capture.ReceiptScan
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.ui.assets.detail.DocumentPickers
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.theme.KronoTheme

object ReceiptTags {
    const val CARD = "receipt_card"
    const val APPLY = "receipt_apply"
    fun field(field: ReceiptField) = "receipt_field_${field.name}"
}

/** Entry point for receipt OCR at the top of the details step, and the receipt's status afterwards. */
@Composable
fun ReceiptScanCard(
    receipt: ReceiptState?,
    pickers: DocumentPickers,
    onReview: () -> Unit,
    onAttachChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(ReceiptTags.CARD)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val done = receipt != null && !receipt.scanning
                Icon(
                    if (done && receipt.applied) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.ReceiptLong,
                    contentDescription = null,
                    tint = if (done && receipt.applied) KronoTheme.status.ok else MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            receipt == null -> "Have the receipt?"
                            receipt.scanning -> "Reading the receipt…"
                            receipt.applied -> "Filled in from the receipt"
                            else -> "Receipt added"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            receipt == null -> "Scan it to fill in the date, price, store and serial. It's read on your phone."
                            receipt.scanning -> "This takes a moment and works offline."
                            else -> "Check the fields below before saving."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (receipt?.scanning == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (receipt == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = pickers.takePhoto) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Take photo", Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = pickers.pickPhoto) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Choose", Modifier.padding(start = 8.dp))
                    }
                }
            } else if (!receipt.scanning) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = receipt.attach, role = Role.Checkbox, onValueChange = onAttachChange),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = receipt.attach, onCheckedChange = null)
                    Text("Keep the photo in this asset's documents", Modifier.padding(start = 8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (receipt.scan != null) TextButton(onClick = onReview) { Text("Review details") }
                    TextButton(onClick = pickers.takePhoto) { Text("Retake") }
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptReviewSheet(
    receipt: ReceiptState,
    form: AssetForm,
    onToggle: (ReceiptField) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scan = receipt.scan ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text("Found on the receipt", style = MaterialTheme.typography.titleLarge)
            Text(
                "Untick anything that was read wrong. You can edit every field afterwards.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            ReceiptFill.found(scan).forEach { field ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = field in receipt.selected, role = Role.Checkbox, onValueChange = { onToggle(field) })
                        .padding(vertical = 8.dp)
                        .testTag(ReceiptTags.field(field)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = field in receipt.selected, onCheckedChange = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(field.display(scan), style = MaterialTheme.typography.bodyLarge)
                        if (field in receipt.conflicts) {
                            Text(
                                "Replaces ${field.current(form)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = KronoTheme.status.dueSoon,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Skip") }
                Button(
                    onClick = onApply,
                    enabled = receipt.selected.isNotEmpty(),
                    modifier = Modifier.testTag(ReceiptTags.APPLY),
                ) { Text("Fill in") }
            }
        }
    }
}

private val ReceiptField.label: String
    get() = when (this) {
        ReceiptField.DATE -> "Purchase date"
        ReceiptField.PRICE -> "Price (total)"
        ReceiptField.SERIAL -> "Serial number"
        ReceiptField.STORE -> "Store (added to notes)"
    }

private fun ReceiptField.display(scan: ReceiptScan): String = when (this) {
    ReceiptField.DATE -> scan.purchaseDate?.let(Formats::date)
    ReceiptField.PRICE -> scan.total?.format()
    ReceiptField.SERIAL -> scan.serialNo
    ReceiptField.STORE -> scan.store
}.orEmpty()

private fun ReceiptField.current(form: AssetForm): String = when (this) {
    ReceiptField.DATE -> form.purchaseDate?.let(Formats::date)
    ReceiptField.PRICE -> Peso.parse(form.price)?.format() ?: form.price
    ReceiptField.SERIAL -> form.serialNo
    ReceiptField.STORE -> null
}.orEmpty()
