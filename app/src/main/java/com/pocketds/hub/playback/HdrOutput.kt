package com.pocketds.hub.playback

import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import com.pocketds.hub.debug.DebugLog

/**
 * Whether a decoder should be asked to turn HDR into ordinary SDR video.
 *
 * The Pocket DS top screen reports HDR10 and HLG support, so Android passes an
 * HDR picture straight through -- but the panel has no HDR brightness boost
 * configured (its SDR-to-HDR ratio and HDR mode are both absent). HDR scenes
 * are mastered for far brighter screens and sit at a fraction of SDR's level,
 * so on this panel they simply looked dim: measured on the downloaded Iron Man
 * 3 (HLG) and Captain America: The Winter Soldier (HDR10 + Dolby Vision
 * profile 8), next to the untagged SDR of Age of Ultron and Bleach.
 *
 * Unknown counts as possibly HDR. Iron Man 3's MKV carries no colour element,
 * so the decoder was created with no colour info at all and only reported HLG
 * once it started (c2.qti.hevc.decoder: color-transfer 7). Asking for SDR
 * output from video that already is SDR changes nothing, so only a stream
 * explicitly tagged as SDR is skipped.
 *
 * Plain ints rather than Format/ColorInfo so this stays a JVM test.
 */
object HdrOutput {

    private val SDR_TRANSFERS = setOf(
        C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_SRGB, C.COLOR_TRANSFER_GAMMA_2_2, C.COLOR_TRANSFER_LINEAR
    )

    /** [enabled] is the HDR setting: off shows HDR as the file has it. */
    fun wantsSdrConversion(isVideo: Boolean, colorTransfer: Int, sdkInt: Int, enabled: Boolean = true): Boolean =
        enabled && isVideo && sdkInt >= Build.VERSION_CODES.S && colorTransfer !in SDR_TRANSFERS
}

/**
 * Asks each HDR video decoder to tone-map to SDR while it decodes
 * (MediaFormat.KEY_COLOR_TRANSFER_REQUEST, Android 12+). Qualcomm decoders do
 * this in hardware, so it costs no GPU pass; a decoder that cannot simply
 * ignores the request and plays HDR as before. It works the same for offline
 * files and streams, because it acts where every frame is decoded.
 */
@OptIn(UnstableApi::class)
internal class HdrToSdrCodecFactory(
    private val base: MediaCodecAdapter.Factory,
    private val enabled: () -> Boolean
) : MediaCodecAdapter.Factory {

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val format = configuration.format
        val isVideo = MimeTypes.isVideo(format.sampleMimeType)
        val transfer = format.colorInfo?.colorTransfer ?: C.INDEX_UNSET
        val convert = HdrOutput.wantsSdrConversion(isVideo, transfer, Build.VERSION.SDK_INT, enabled())
        if (convert) {
            configuration.mediaFormat.setInteger(
                MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            )
        }
        if (isVideo) {
            DebugLog.log(
                "player",
                "video decoder ${configuration.codecInfo.name} ${format.sampleMimeType} " +
                    "colour=${format.colorInfo} sdrRequested=$convert"
            )
        }
        return base.createAdapter(configuration)
    }
}
