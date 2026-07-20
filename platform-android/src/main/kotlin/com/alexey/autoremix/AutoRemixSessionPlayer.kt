package com.alexey.autoremix

import android.net.Uri
import android.os.Looper
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

    private fun commands(isSeekable: Boolean) = Player.Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_TIMELINE)
        .add(Player.COMMAND_GET_METADATA)
        .add(Player.COMMAND_RELEASE)
        .apply {
            if (isSeekable) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
        .build()

    override fun getState(): State {
        val playback = RemixEngineService.playbackUiSnapshot
        val timeline = playback.trackTimeline
        val durationMs = timeline.durationMs.coerceAtLeast(1L)
        val isSeekable = false
        val artworkUri = RemixEngineService.currentArtworkUri
            .takeIf(String::isNotBlank)
            ?.let(Uri::parse)
        val metadata = MediaMetadata.Builder()
            .setTitle(RemixEngineService.currentTrack)
            .setSubtitle(RemixEngineService.currentMeta)
            .setArtist("AutoRemix · on-device")
            .setArtworkUri(artworkUri)
            .build()
        val item = MediaItem.Builder()
            .setMediaId("autoremix-${timeline.trackId}")
            .setMediaMetadata(metadata)
            .build()
        val data = MediaItemData.Builder("autoremix-${timeline.trackId}")
            .setMediaItem(item)
            .setMediaMetadata(metadata)
            .setIsSeekable(isSeekable)
            .setDurationUs(durationMs * 1_000L)
            .build()
        return State.Builder()
            .setAvailableCommands(commands(isSeekable))
            .setPlaylist(listOf(data))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (RemixEngineService.running) Player.STATE_READY else Player.STATE_IDLE)
            .setPlayWhenReady(
                RemixEngineService.running && !RemixEngineService.paused,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setContentPositionMs(timeline.currentPositionMs.coerceIn(0L, durationMs))
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
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun refresh() = invalidateState()
}
