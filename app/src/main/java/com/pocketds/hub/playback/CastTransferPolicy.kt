package com.pocketds.hub.playback

import java.net.URI

internal enum class CastTransferStage { LOCAL, PREPARING, REMOTE }

internal object CastTransferPolicy {
    fun afterLoad(succeeded: Boolean) = if (succeeded) CastTransferStage.REMOTE else CastTransferStage.LOCAL

    /** Cast fetches the URL on the TV, not on the Pocket DS. */
    fun receiverUrl(base: String, resource: String): String? {
        if (!resource.startsWith("/v1/cast/") || resource.startsWith("//")) return null
        val parsed = runCatching { URI(base.trimEnd('/')) }.getOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.userInfo != null || parsed.query != null || parsed.fragment != null) return null
        val host = parsed.host?.lowercase() ?: return null
        if (host == "localhost" || host.endsWith(".local") || host.startsWith("127.") ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("172.") || host.startsWith("100.") || host == "::1") return null
        return parsed.toString().trimEnd('/') + resource
    }
}
