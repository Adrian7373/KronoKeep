package com.kronokeep.app.data.capture

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kronokeep.app.domain.capture.CodeKind
import com.kronokeep.app.domain.capture.OcrLine
import com.kronokeep.app.domain.capture.ReceiptTextReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Receipt OCR on a full-resolution still, entirely on the device (bundled Latin model).
 * `InputImage.fromFilePath` applies EXIF rotation, so boxes come back in upright coordinates.
 */
@Singleton
class MlKitReceiptTextReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReceiptTextReader {

    override suspend fun read(imageUri: String): List<OcrLine> {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, Uri.parse(imageUri)) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(image).await().toOcrLines()
        } finally {
            recognizer.close()
        }
    }
}

fun Text.toOcrLines(): List<OcrLine> = textBlocks.flatMap { block ->
    block.lines.mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        OcrLine(line.text, box.left, box.top, box.right, box.bottom)
    }
}

/** Null for formats we don't scan. */
fun Barcode.codeKind(): CodeKind? = when (format) {
    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E -> CodeKind.PRODUCT
    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
    Barcode.FORMAT_CODABAR, Barcode.FORMAT_ITF -> CodeKind.LINEAR
    Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417, Barcode.FORMAT_AZTEC -> CodeKind.MATRIX
    else -> null
}

/** Minimal Task → coroutine bridge, so we don't need kotlinx-coroutines-play-services for one call. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
