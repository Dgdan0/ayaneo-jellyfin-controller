package com.pocketds.hub.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import com.pocketds.hub.model.PlaybackCapabilities
import com.pocketds.hub.model.PlaybackDevice
import com.pocketds.hub.model.PlaybackPrepareBody
import com.pocketds.hub.settings.Prefs
import java.util.UUID

/** Builds the Jellyfin device profile from decoders and the active top display. */
object PlaybackCapabilitiesProbe {
    fun prepare(
        context: Context,
        startMode: String,
        positionMillis: Long = 0L
    ): PlaybackPrepareBody = PlaybackPrepareBody(
        startMode = startMode,
        positionMillis = positionMillis,
        device = PlaybackDevice(
            id = installationId(context),
            name = "Pocket DS",
            version = "0.1"
        ),
        capabilities = inspect(context)
    )

    fun inspect(context: Context): PlaybackCapabilities {
        val video = linkedSetOf<String>()
        val audio = linkedSetOf<String>()
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence().filterNot { it.isEncoder }.flatMap { it.supportedTypes.asSequence() }
                .map { it.lowercase() }.forEach { mime ->
                    when (mime) {
                        "video/avc" -> video += "h264"
                        "video/hevc" -> video += "hevc"
                        "video/x-vnd.on2.vp8" -> video += "vp8"
                        "video/x-vnd.on2.vp9" -> video += "vp9"
                        "video/av01" -> video += "av1"
                        "video/mpeg2" -> video += "mpeg2video"
                        "video/mp4v-es" -> video += "mpeg4"
                        "audio/mp4a-latm" -> audio += "aac"
                        "audio/mpeg" -> audio += "mp3"
                        "audio/ac3" -> audio += "ac3"
                        "audio/eac3", "audio/eac3-joc" -> audio += "eac3"
                        "audio/opus" -> audio += "opus"
                        "audio/vorbis" -> audio += "vorbis"
                        "audio/flac" -> audio += "flac"
                        "audio/alac" -> audio += "alac"
                    }
                }
        }
        if (video.isEmpty()) video += "h264"
        if (audio.isEmpty()) audio += listOf("aac", "mp3")

        val windowManager = context.getSystemService(WindowManager::class.java)
        val size = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds.let {
                it.width() to it.height()
            }
        } else {
            @Suppress("DEPRECATION")
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        val display = if (Build.VERSION.SDK_INT >= 30) context.display else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val hdr = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 26) {
            display?.hdrCapabilities?.supportedHdrTypes?.forEach {
                when (it) {
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdr += "HDR10"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> hdr += "HLG"
                    android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> hdr += "DolbyVision"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> hdr += "HDR10Plus"
                }
            }
        }
        val channels = runCatching {
            val manager = context.getSystemService(AudioManager::class.java)
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
                .flatMap { it.channelCounts.toList() }.maxOrNull() ?: 2
        }.getOrDefault(2).coerceIn(2, 8)
        return PlaybackCapabilities(
            width = size.first,
            height = size.second,
            maxAudioChannels = channels,
            videoCodecs = video.toList(),
            audioCodecs = audio.toList(),
            hdrTypes = hdr
        )
    }

    private fun installationId(context: Context): String {
        val prefs = Prefs.of(context)
        prefs.getString(KEY_DEVICE_ID, "")?.takeIf { it.isNotEmpty() }?.let { return it }
        val androidID = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty().takeIf { it.isNotEmpty() }
        val id = "pocketds-" + (androidID ?: UUID.randomUUID().toString().replace("-", ""))
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private const val KEY_DEVICE_ID = "playback_device_id"
}
