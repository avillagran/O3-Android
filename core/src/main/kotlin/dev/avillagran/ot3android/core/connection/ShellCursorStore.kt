package dev.avillagran.ot3android.core.connection

/**
 * Where the shell stream of each environment resumes (`afterSequence`), persisted so a
 * process restart can resume instead of taking a full snapshot. Keyed by the local
 * environment id. Not secret.
 */
interface ShellCursorStore {
    suspend fun cursor(environmentId: String): Long?
    suspend fun setCursor(environmentId: String, cursor: Long?)
}
