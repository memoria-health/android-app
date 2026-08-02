package com.memoria.caregiver.data.realtime

import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One replicated row change, normalized off the SDK's type.
 *
 * The Swift handlers switch over supabase-swift's `AnyAction`. Its Kotlin
 * counterpart, [PostgresAction], carries extra baggage the port has no use for
 * (columns, commit timestamp, the plugin's serializer) and one extra case,
 * `Select`, that a `postgres_changes` subscription never emits — it belongs to
 * `selectAsFlow`. Mapping to this three-case type at the boundary keeps the
 * handlers free of the SDK, which is what makes them testable from plain JSON.
 */
sealed interface RowChange {

    /** The label the DEBUG log prints, matching the Swift's `kind` string. */
    val kind: String

    data class Insert(val record: JsonObject) : RowChange {
        override val kind: String get() = "INSERT"
    }

    data class Update(val record: JsonObject) : RowChange {
        override val kind: String get() = "UPDATE"
    }

    /**
     * Postgres sends only the replica-identity columns on a delete, so this
     * carries `oldRecord` and never a full row. Every delete handler reads
     * nothing but `id` from it.
     */
    data class Delete(val oldRecord: JsonObject) : RowChange {
        override val kind: String get() = "DELETE"
    }
}

/**
 * Null for anything that is not a row change — today only `Select`, which
 * belongs to `selectAsFlow` and never reaches a `postgres_changes`
 * subscription. Swift's `AnyAction` has the three cases and nothing else, so
 * ignoring the rest is what keeps the two ports equivalent.
 */
fun PostgresAction.toRowChange(): RowChange? = when (this) {
    is PostgresAction.Insert -> RowChange.Insert(record)
    is PostgresAction.Update -> RowChange.Update(record)
    is PostgresAction.Delete -> RowChange.Delete(oldRecord)
    else -> null
}

/**
 * The row's `id` as a string, or null when the key is missing or is not a JSON
 * string.
 *
 * Swift parses it as a `UUID` and drops the change when that fails. The model
 * layer already decided that ids are `String` on Android
 * (`DeviceModels.kt` header), so there is no UUID to parse. Nothing is lost:
 * every cached id came from the server, so a value that is not a real id
 * matches nothing and the handler removes nothing either way.
 */
internal fun JsonObject.rowId(): String? =
    (this["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
