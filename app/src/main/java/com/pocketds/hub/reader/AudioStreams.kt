package com.pocketds.hub.reader

import android.content.Context
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.pocketds.hub.net.HubClient
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * How the reading players read a streamed track (#19, A3), the audiobook's and
 * read along's alike: from this device's copy, else from the hub with the
 * bearer and the credential gate (HubClient.audioHttp), keeping what was read
 * under the track's key (AudiobookStream.cacheKey), the least recently played
 * dropped first past [BUDGET_BYTES]. A file on the device (an audiobook out of
 * its ZIP) is read as it is, never copied into the cache.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AudioStreams {
    /** What the streamed tracks may keep on this device: about 18 hours of a 64 kbps book. */
    const val BUDGET_BYTES = 512L shl 20
    /** The head of the next track fetched ahead: about four minutes of a 64 kbps book. */
    const val PREFETCH_BYTES = 2L shl 20
    private const val FOLDER = "reading-audio-stream"

    @Volatile private var cache: SimpleCache? = null

    /** The one cache for the process: SimpleCache refuses a second instance on its folder. */
    fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: context.applicationContext.let { app ->
            SimpleCache(File(app.cacheDir, FOLDER), LeastRecentlyUsedCacheEvictor(BUDGET_BYTES), StandaloneDatabaseProvider(app))
        }.also { cache = it }
    }

    private fun streamed(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(HubClient.shared(context).audioHttp))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** A track from the cache or the hub; a file:// part straight from the device. */
    fun dataSources(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext, streamed(context.applicationContext))

    /**
     * The players' sources: [dataSources], and a refusal the hub will repeat
     * (409, 412) handed to the player at once rather than retried for seconds,
     * so a changed book is read again straight away.
     */
    fun mediaSources(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSources(context)).setLoadErrorHandlingPolicy(NoRetryOnRefusal)

    /** The HTTP status a player's error came from, or null for any other failure. */
    fun httpStatus(error: Throwable?): Int? = generateSequence(error) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    /**
     * Fetches the first [PREFETCH_BYTES] of a streamed [part] into the cache,
     * so the next track starts at once even if the network falters at the
     * change. What is already kept is not fetched again. False when it could
     * not (offline, refused); cancelling the caller stops the transfer.
     */
    suspend fun prefetch(context: Context, part: AudiobookPart): Boolean {
        if (!part.streamed || part.cacheKey.isEmpty()) return false
        val writer = CacheWriter(streamed(context.applicationContext).createDataSourceForDownloading(),
            DataSpec.Builder().setUri(part.uri).setKey(part.cacheKey).setLength(PREFETCH_BYTES).build(), null, null)
        return try {
            runInterruptible(Dispatchers.IO) { writer.cache() }
            true
        } catch (_: IOException) {
            false
        } finally {
            writer.cancel()
        }
    }

    /** How much of these books' tracks this device keeps. */
    fun cachedBytes(context: Context, sourceItemIds: Collection<String>): Long {
        val kept = cache(context)
        return keys(kept, sourceItemIds).sumOf { key -> kept.getCachedSpans(key).sumOf { it.length } }
    }

    /** Lets go of these books' tracks on this device (Remove offline copy). */
    fun remove(context: Context, sourceItemIds: Collection<String>): Boolean {
        val kept = cache(context)
        return keys(kept, sourceItemIds).map { key -> runCatching { kept.removeResource(key) }.isSuccess }.all { it }
    }

    private fun keys(kept: SimpleCache, sourceItemIds: Collection<String>): List<String> {
        val prefixes = sourceItemIds.filter(String::isNotBlank).map(AudiobookStream::cachePrefix)
        return kept.keys.filter { key -> prefixes.any(key::startsWith) }
    }

    /** The default policy, but a refusal the hub would only repeat is not retried. */
    private object NoRetryOnRefusal : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (httpStatus(loadErrorInfo.exception) in REFUSED) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
    }

    /** The hub's answers that say the book's files are not what the player asked for. */
    private val REFUSED = setOf(409, 412)
}
