package com.kronokeep.app.data.document

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.kronokeep.app.domain.document.DocumentRules
import com.kronokeep.app.domain.model.PreparedFile
import com.kronokeep.app.domain.repository.DocumentImporter
import com.kronokeep.app.domain.repository.ImportResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a picked or captured file into app-private storage (`files/documents/`). Images are
 * re-encoded on the device: EXIF rotation applied, longest side ≤ 2048 px, JPEG quality 85.
 * PDFs are copied as-is. Everything is hashed (SHA-256) and capped at 15 MB.
 */
@Singleton
class AndroidDocumentImporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentImporter {

    private val resolver: ContentResolver get() = context.contentResolver
    private val dir: File get() = File(context.filesDir, "documents").apply { mkdirs() }

    override suspend fun import(sourceUri: String, documentId: String, declaredMimeType: String?): ImportResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(sourceUri)
            val mime = MimeTypes.normalize(resolver.getType(uri))
                ?: MimeTypes.normalize(declaredMimeType)
                ?: MimeTypes.fromFileName(displayName(uri) ?: uri.lastPathSegment)
            if (mime == null || !DocumentRules.isAllowed(mime)) return@withContext ImportResult.UnsupportedType

            var out: File? = null
            try {
                val prepared = if (mime.startsWith("image/")) {
                    val jpeg = File(dir, "$documentId.jpg")
                    out = jpeg
                    if (compressImage(uri, jpeg, mime)) {
                        jpeg to "image/jpeg"
                    } else {
                        // Decoder can't read it (e.g. HEIC before Android 9): keep the original bytes.
                        jpeg.delete()
                        val raw = File(dir, "$documentId.${DocumentRules.extensionFor(mime)}")
                        out = raw
                        if (!copyLimited(uri, raw)) return@withContext ImportResult.TooLarge(raw.length())
                        raw to mime
                    }
                } else {
                    val raw = File(dir, "$documentId.${DocumentRules.extensionFor(mime)}")
                    out = raw
                    if (!copyLimited(uri, raw)) return@withContext ImportResult.TooLarge(raw.length())
                    raw to mime
                }
                val (file, finalMime) = prepared
                val size = file.length()
                when {
                    size == 0L -> ImportResult.Failed("The file is empty")
                    size > DocumentRules.MAX_BYTES -> ImportResult.TooLarge(size)
                    else -> {
                        out = null // keep it
                        ImportResult.Success(PreparedFile(file.absolutePath, finalMime, size, Sha256.hex(file)))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult.Failed(e.message ?: "Couldn't read the file")
            } finally {
                out?.delete()
            }
        }

    override suspend fun discard(localPath: String) {
        withContext(Dispatchers.IO) { File(localPath).delete() }
    }

    /** Returns false if the image couldn't be decoded. */
    private fun compressImage(uri: Uri, target: File, mime: String): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageScaling.sampleSize(bounds.outWidth, bounds.outHeight, DocumentRules.MAX_IMAGE_SIDE_PX)
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: return false
        val size = ImageScaling.fit(decoded.width, decoded.height, DocumentRules.MAX_IMAGE_SIDE_PX)
        val scaled = if (size.width != decoded.width || size.height != decoded.height) {
            Bitmap.createScaledBitmap(decoded, size.width, size.height, true)
        } else {
            decoded
        }
        val rotation = if (mime == "image/png") null else exifTransform(uri)
        val oriented = rotation?.let { Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, it, true) } ?: scaled
        // JPEG has no alpha: flatten transparent PNGs onto white instead of black.
        val opaque = if (oriented.hasAlpha()) {
            Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(oriented, 0f, 0f, null) }
            }
        } else {
            oriented
        }
        target.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, DocumentRules.JPEG_QUALITY, it) }
        setOf(decoded, scaled, oriented, opaque).forEach(Bitmap::recycle)
        return true
    }

    private fun exifTransform(uri: Uri): Matrix? {
        val orientation = try {
            open(uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: IOException) {
            return null
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }

    /** Streams [uri] into [target]; false (and a partial file) once it passes the 15 MB cap. */
    private fun copyLimited(uri: Uri, target: File): Boolean {
        open(uri).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > DocumentRules.MAX_BYTES) return false
                    output.write(buffer, 0, read)
                }
            }
        }
        return true
    }

    private fun open(uri: Uri) = resolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}

/** Camera captures go to a FileProvider-shared cache file, imported, then deleted. */
@Singleton
class CaptureFiles @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.cacheDir, "capture").apply { mkdirs() }
    private val authority: String get() = "${context.packageName}.files"

    fun newCaptureUri(): String =
        FileProvider.getUriForFile(context, authority, File(dir, "${UUID.randomUUID()}.jpg")).toString()

    fun delete(uri: String) {
        val parsed = Uri.parse(uri)
        if (parsed.authority != authority) return
        parsed.lastPathSegment?.let { File(dir, it).delete() }
    }
}

/** Remote PDFs need a seekable file for PdfRenderer. They live in cache only while the viewer is open. */
@Singleton
class ViewerFiles @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.cacheDir, "viewer").apply { mkdirs() }

    suspend fun writeTemp(name: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        File(dir, name).apply { writeBytes(bytes) }
    }

    fun delete(file: File) {
        file.delete()
    }
}
