package com.kronokeep.app.domain.asset

import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.repository.AssetRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

sealed interface SaveAssetResult {
    data class Saved(val id: String) : SaveAssetResult
    data class Invalid(val errors: Map<AssetField, AssetError>) : SaveAssetResult
    data object NotFound : SaveAssetResult
}

class CreateAssetUseCase @Inject constructor(
    private val repository: AssetRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: AssetDraft): SaveAssetResult {
        val clean = draft.normalized()
        val errors = AssetValidator.validate(clean, LocalDate.now(clock))
        if (errors.isNotEmpty()) return SaveAssetResult.Invalid(errors)
        return SaveAssetResult.Saved(repository.create(clean))
    }
}

class UpdateAssetUseCase @Inject constructor(
    private val repository: AssetRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String, draft: AssetDraft): SaveAssetResult {
        val existing = repository.get(id) ?: return SaveAssetResult.NotFound
        // Category is fixed after creation: schedules and presets hang off it.
        val clean = draft.copy(category = existing.category).normalized()
        val errors = AssetValidator.validate(clean, LocalDate.now(clock))
        if (errors.isNotEmpty()) return SaveAssetResult.Invalid(errors)
        return if (repository.update(id, clean)) SaveAssetResult.Saved(id) else SaveAssetResult.NotFound
    }
}

class SetAssetArchivedUseCase @Inject constructor(private val repository: AssetRepository) {
    suspend operator fun invoke(id: String, archived: Boolean): Boolean = repository.setArchived(id, archived)
}

class DeleteAssetUseCase @Inject constructor(private val repository: AssetRepository) {
    suspend operator fun invoke(id: String): Boolean = repository.delete(id)
}
