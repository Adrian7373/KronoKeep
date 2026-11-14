package com.kronokeep.app.domain.repository

import com.kronokeep.app.domain.model.Document
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.DocumentSource
import com.kronokeep.app.domain.model.PreparedFile
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeForAsset(assetId: String): Flow<List<Document>>

    suspend fun get(id: String): Document?

    /** Records [file] (already in app-private storage) and queues its upload. Returns the document id. */
    suspend fun add(documentId: String, assetId: String, kind: DocumentKind, file: PreparedFile): String

    suspend fun delete(id: String): Boolean

    /** The local copy if it still exists, otherwise a fresh short-lived signed URL. */
    suspend fun source(id: String): Result<DocumentSource>
}

/** Turns a user-picked file into a compressed, hashed app-private copy. Implemented on Android. */
interface DocumentImporter {
    /** [sourceUri] is a content:// or file:// URI string. [declaredMimeType] comes from the picker, if known. */
    suspend fun import(sourceUri: String, documentId: String, declaredMimeType: String?): ImportResult

    /** Removes an app-private copy that was never recorded. */
    suspend fun discard(localPath: String)
}

sealed interface ImportResult {
    data class Success(val file: PreparedFile) : ImportResult
    data object UnsupportedType : ImportResult
    data class TooLarge(val sizeBytes: Long) : ImportResult
    data class Failed(val message: String) : ImportResult
}
