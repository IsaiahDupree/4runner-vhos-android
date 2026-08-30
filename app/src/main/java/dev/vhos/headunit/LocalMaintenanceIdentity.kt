package dev.vhos.headunit

import android.content.Context
import dev.vhos.maintenance.MaintenanceActor
import dev.vhos.maintenance.MaintenanceActorSource
import dev.vhos.maintenance.MaintenanceIds

/**
 * Stable per-install identity for append-only maintenance authorship.
 *
 * This is an identity label, not an authentication credential. The SQLCipher database and
 * Android Keystore protect the ledger; the typed actor ULID makes every local mutation traceable
 * without pretending that all Android installations are the same person.
 */
internal object LocalMaintenanceIdentity {
    private const val PREFERENCES = "vhos.maintenance.identity"
    private const val ACTOR_ID = "owner_actor_id"

    @Synchronized
    fun owner(context: Context): MaintenanceActor {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val retained = preferences.getString(ACTOR_ID, null)?.takeIf { candidate ->
            runCatching { MaintenanceIds.requireActor(candidate) }.isSuccess
        }
        val actorId = retained ?: MaintenanceIds.actor().also { generated ->
            check(preferences.edit().putString(ACTOR_ID, generated).commit()) {
                "The stable maintenance actor identity could not be persisted."
            }
        }
        return MaintenanceActor(
            source = MaintenanceActorSource.OWNER,
            actorId = actorId,
            displayName = "Android local owner",
        ).validate()
    }
}
