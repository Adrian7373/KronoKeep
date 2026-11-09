package com.kronokeep.app.domain.common

import java.util.UUID

/** Client-side UUIDs, so rows created offline never need remapping. Injected so tests can be deterministic. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random = IdGenerator { UUID.randomUUID().toString() }
    }
}
