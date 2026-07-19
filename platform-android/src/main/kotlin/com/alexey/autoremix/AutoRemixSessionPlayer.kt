package com.alexey.autoremix

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Media3 control surface for the rendered PCM player. Audio remains owned by SceneAudioPlayer. */
@UnstableApi
class AutoRemixSessionPlayer(private val controls: Controls) : SimpleBasePlayer(Looper.getMainLooper()) {
    fun interface Controls {
        fun dispatch(action: String, value: Long)
    }

    private val commands = Player.Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_TIMELINE)
        .add(Player.COMMAND_GET_METADATA)
        .add(Player.COMMAND_RELEASE)
        .build()

    override fun getState(): State {
        val durationMs = RemixEngineService.playbackDurationMs.coerceAtLeast(1L)
        val metadata = MediaMetadata.Builder()
            .setTitle(RemixEngineService.currentTrack)
            .setSubtitle(RemixEngineService.currentMeta)
            .setArtist("AutoRemix · on-device")
            .build()
        val item = MediaItem.Builder()
            .setMediaId("autoremix-current")
            .setMediaMetadata(metadata)
            .build()
        val data = MediaItemData.Builder("autoremix-current")
            .setMediaItem(item)
            .setMediaMetadata(metadata)
            .setIsSeekable(true)
            .setDurationUs(durationMs * 1_000L)
            .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(data))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (RemixEngineService.running) Player.STATE_READY else Player.STATE_IDLE)
            .setPlayWhenReady(
                RemixEngineService.running && !RemixEngineService.paused,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setContentPositionMs(RemixEngineService.playbackPositionMs.coerceIn(0L, durationMs))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        controls.dispatch(
            if (playWhenReady) {
                if (RemixEngineService.running) RemixEngineService.ACTION_RESUME else RemixEngineService.ACTION_START
            } else {
                RemixEngineService.ACTION_PAUSE
            },
            0L,
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT -> controls.dispatch(RemixEngineService.ACTION_SKIP, 0L)
            Player.COMMAND_SEEK_TO_PREVIOUS -> controls.dispatch(RemixEngineService.ACTION_BACK, 0L)
            else -> controls.dispatch(
                RemixEngineService.ACTION_SEEK,
                if (positionMs == C.TIME_UNSET) 0L else positionMs,
            )
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun refresh() = invalidateState()
}
