package com.kronokeep.app.domain.document

import com.kronokeep.app.domain.common.IdGenerator
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.DocumentImporter
import com.kronokeep.app.domain.repository.DocumentRepository
import com.kronokeep.app.domain.repository.ImportResult
import javax.inject.Inject

sealed interface AddDocumentResult {
    data class Added(val documentId: String) : AddDocumentResult
    data object AssetNotFound : AddDocumentResult
    data object UnsupportedType : AddDocumentResult
    data class TooLarge(val sizeBytes: Long) : AddDocumentResult
    data class Failed(val message: String) : AddDocumentResult
}

class AddDocumentUseCase @Inject constructor(
    private val assets: AssetRepository,
    private val documents: DocumentRepository,
    private val importer: DocumentImporter,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(
        assetId: String,
        kind: DocumentKind,
        sourceUri: String,
        declaredMimeType: String?,
    ): AddDocumentResult {
        if (assets.get(assetId) == null) return AddDocumentResult.AssetNotFound
        val documentId = ids.newId()
        return when (val imported = importer.import(sourceUri, documentId, declaredMimeType)) {
            ImportResult.UnsupportedType -> AddDocumentResult.UnsupportedType
            is ImportResult.TooLarge -> AddDocumentResult.TooLarge(imported.sizeBytes)
            is ImportResult.Failed -> AddDocumentResult.Failed(imported.message)
            is ImportResult.Success -> {
                val file = imported.file
                // Defence in depth: the importer should never hand back something the server would reject.
                if (!DocumentRules.isAllowed(file.mimeType)) {
                    importer.discard(file.localPath)
                    return AddDocumentResult.UnsupportedType
                }
                if (file.sizeBytes > DocumentRules.MAX_BYTES) {
                    importer.discard(file.localPath)
                    return AddDocumentResult.TooLarge(file.sizeBytes)
                }
                try {
                    AddDocumentResult.Added(documents.add(documentId, assetId, kind, file))
                } catch (e: Exception) {
                    importer.discard(file.localPath)
                    throw e
                }
            }
        }
    }
}
