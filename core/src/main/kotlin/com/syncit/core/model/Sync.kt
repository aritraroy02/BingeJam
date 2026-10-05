package com.syncit.core.model

/** A command the sync engine wants applied to the local streaming app. */
sealed interface SyncCommand {
    data object Play : SyncCommand
    data object Pause : SyncCommand
    data class SeekTo(val positionMs: Long) : SyncCommand
}

enum class BroadcastReason { PLAY, PAUSE, SEEK }

/** A local user action the engine wants written to the room. */
data class StateBroadcast(
    val status: PlaybackStatus,
    val positionMs: Long,
    val reason: BroadcastReason,
)

/**
 * How much we can sync with the current app, picked at runtime from its supported actions
 * and how its position behaves.
 */
enum class SyncMode {
    /** Play/pause + seek + drift correction. */
    FULL,

    /** App has no SEEK_TO: play/pause only; whoever is ahead pauses briefly so others catch up. */
    PLAY_PAUSE_CATCH_UP,

    /** App reports position 0 / never updates it: play/pause only, no timeline sync. */
    PLAY_PAUSE_ONLY,

    /** No session, or the app can't be played/paused: manual "3, 2, 1, play" countdown. */
    MANUAL_COUNTDOWN,
}

enum class SyncStatus {
    IDLE,
    WAITING_FOR_ROOM,
    NO_NOTIFICATION_ACCESS,
    NOT_CONFIGURED,
    APP_NOT_OPEN,
    SESSION_LOST,
    SYNCING,
    CORRECTING,
    CATCHING_UP,
}

/** Everything one engine call wants done. */
data class EngineOutput(
    val commands: List<SyncCommand> = emptyList(),
    val broadcast: StateBroadcast? = null,
    /** Our live position to publish to members/{uid} for the drift overlay. */
    val positionReport: Long? = null,
) {
    operator fun plus(other: EngineOutput): EngineOutput = EngineOutput(
        commands = commands + other.commands,
        broadcast = other.broadcast ?: broadcast,
        positionReport = other.positionReport ?: positionReport,
    )

    companion object {
        val NONE = EngineOutput()
    }
}
