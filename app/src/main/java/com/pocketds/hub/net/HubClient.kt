package com.pocketds.hub.net

import android.content.Context
import coil.ImageLoader
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.ActionAck
import com.pocketds.hub.model.ActivityResponse
import com.pocketds.hub.model.CreateRequestBody
import com.pocketds.hub.model.DiscoverResponse
import com.pocketds.hub.model.GrabBody
import com.pocketds.hub.model.GrabResponse
import com.pocketds.hub.model.ReleasesResponse
import com.pocketds.hub.model.ReleaseTargetsResponse
import com.pocketds.hub.model.RequestOptions
import com.pocketds.hub.model.CreateRequestResponse
import com.pocketds.hub.model.HealthResponse
import com.pocketds.hub.model.HomeResponse
import com.pocketds.hub.model.HubErrorBody
import com.pocketds.hub.model.MediaDetail
import com.pocketds.hub.model.NotificationsResponse
import com.pocketds.hub.model.PersonResponse
import com.pocketds.hub.model.SearchResponse
import com.pocketds.hub.model.LibraryResponse
import com.pocketds.hub.model.LibraryItemsResponse
import com.pocketds.hub.model.LibraryItemResponse
import com.pocketds.hub.model.LibrarySeasonsResponse
import com.pocketds.hub.model.LibraryEpisodesResponse
import com.pocketds.hub.model.LibraryStateRequest
import com.pocketds.hub.model.UsersResponse
import com.pocketds.hub.model.PlaybackEventBody
import com.pocketds.hub.model.PlaybackPrepareBody
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.model.PlaybackSelectBody
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.OfflinePrepareBody
import com.pocketds.hub.model.OfflinePrepareResponse
import com.pocketds.hub.model.OfflineProgressSyncBody
import com.pocketds.hub.model.OfflineProgressSyncResponse
import com.pocketds.hub.model.OfflineSelectionResponse
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.NotificationLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Everything the app knows how to ask the hub.
 *
 * An interface so the whole UI can be built and driven on the device against
 * [FakeHubApi] before the hub exists or while it is being changed -- which is
 * what let phases A1 and A3 proceed independently of the Go work.
 */
interface HubApi {
    suspend fun health(): HubResult<HealthResponse>
    suspend fun scanJellyfinLibrary(): HubResult<ActionAck>
    suspend fun users(): HubResult<UsersResponse>
    suspend fun home(): HubResult<HomeResponse>
    suspend fun library(): HubResult<LibraryResponse>
    suspend fun libraryItems(
        viewId: String,
        page: Int = 1,
        sort: String = "name",
        order: String = "asc"
    ): HubResult<LibraryItemsResponse>
    suspend fun libraryItem(itemId: String): HubResult<LibraryItemResponse>
    suspend fun librarySeasons(seriesId: String): HubResult<LibrarySeasonsResponse>
    suspend fun libraryEpisodes(
        seriesId: String,
        seasonId: String,
        page: Int = 1
    ): HubResult<LibraryEpisodesResponse>
    suspend fun librarySearch(query: String, page: Int = 1): HubResult<LibraryItemsResponse>
    suspend fun libraryFavorites(page: Int = 1): HubResult<LibraryItemsResponse>
    suspend fun updateLibraryState(
        itemId: String,
        state: LibraryStateRequest
    ): HubResult<LibraryItemResponse>
    suspend fun seriesPlayTarget(seriesId: String): HubResult<SeriesPlayTargetResponse>
    suspend fun preparePlayback(
        itemId: String,
        body: PlaybackPrepareBody
    ): HubResult<PlaybackPrepareResponse>
    suspend fun selectPlayback(
        sessionId: String,
        body: PlaybackSelectBody,
        userId: String = ""
    ): HubResult<PlaybackPrepareResponse>
    suspend fun playbackEvent(
        sessionId: String,
        body: PlaybackEventBody,
        userId: String = ""
    ): HubResult<ActionAck>
    suspend fun deletePlayback(sessionId: String, userId: String = ""): HubResult<ActionAck>
    suspend fun offlineSelection(seriesId: String): HubResult<OfflineSelectionResponse>
    suspend fun prepareOffline(body: OfflinePrepareBody): HubResult<OfflinePrepareResponse>
    suspend fun renewOffline(grantId: String): HubResult<OfflineManifest>
    suspend fun syncOfflineProgress(body: OfflineProgressSyncBody): HubResult<OfflineProgressSyncResponse>
    suspend fun search(query: String, page: Int = 1): HubResult<SearchResponse>
    suspend fun mediaDetail(key: String): HubResult<MediaDetail>
    suspend fun person(id: Int, sort: String = "release"): HubResult<PersonResponse>
    suspend fun requestMedia(
        key: String,
        profileId: Int? = null,
        rootFolder: String? = null,
        serverId: Int? = null,
        seasons: kotlinx.serialization.json.JsonElement? = null
    ): HubResult<CreateRequestResponse>
    suspend fun discover(): HubResult<DiscoverResponse>
    suspend fun discoverRow(row: String, page: Int): HubResult<DiscoverResponse>
    suspend fun requestOptions(key: String): HubResult<RequestOptions>
    suspend fun releaseTargets(key: String, season: Int): HubResult<ReleaseTargetsResponse>
    suspend fun releases(key: String, season: Int = 0, episode: Int = 0): HubResult<ReleasesResponse>
    suspend fun grab(
        key: String,
        releaseId: String,
        season: Int = 0,
        episode: Int = 0
    ): HubResult<GrabResponse>
    suspend fun activity(includeFinished: Boolean = false): HubResult<ActivityResponse>
    suspend fun notifications(limits: NotificationLimits = NotificationLimits()): HubResult<NotificationsResponse>
    suspend fun downloadAction(id: String, action: String): HubResult<ActionAck>
    suspend fun deleteDownload(id: String, deleteFiles: Boolean): HubResult<ActionAck>
    suspend fun removeFromQueue(
        service: String,
        queueId: Int,
        removeFromClient: Boolean = true,
        blocklist: Boolean = false,
        search: Boolean = false
    ): HubResult<ActionAck>
    /** Absolute URL for an image path the hub returned. */
    fun imageUrl(hubPath: String): String
    /** Absolute URL for a session-bound stream or subtitle returned by the hub. */
    fun playbackUrl(hubPath: String): String
    /** The bytes of a small session-bound playback resource such as a text subtitle. */
    suspend fun playbackBytes(hubPath: String): HubResult<ByteArray> =
        HubResult.Failed(FailureKind.UNKNOWN, "Playback resource loading is unavailable")
}

/**
 * The real client.
 *
 * Two OkHttp clients sharing one connection pool. This is not premature: OkHttp
 * defaults to five concurrent requests per host, so a single client would let a
 * screenful of poster loads queue ahead of the search request that the user is
 * actually waiting on. `newBuilder()` shares the pool -- which is what we want,
 * one TLS handshake -- but also shares the Dispatcher, so the image client has
 * to be given its own explicitly.
 */
class HubClient(private val context: Context) : HubApi {

    private val json = Json {
        // The hub will grow fields; an old APK must not crash on them.
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val api: OkHttpClient = OkHttpClient.Builder()
        // Fail fast if the hub is simply not there, but be patient once it
        // answers: a cold search goes out to TMDB and back.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // A hard ceiling the retry loop must fit inside.
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cache(okhttp3.Cache(File(context.cacheDir, "hub-http"), 32L * 1024 * 1024))
        .addInterceptor { chain ->
            val token = HubSettings.token(context)
            val request = if (token.isEmpty()) {
                chain.request()
            } else {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .apply {
                        HubSettings.userId(context).takeIf {
                            it.isNotEmpty() && chain.request().header(JELLYFIN_USER_HEADER) == null
                        }?.let {
                            header(JELLYFIN_USER_HEADER, it)
                        }
                    }
                    .build()
            }
            val started = System.currentTimeMillis()
            val response = chain.proceed(request)
            // The Authorization header is deliberately never logged: a typo'd
            // real token would then sit in the trace in clear text.
            DebugLog.log(
                "net",
                "${request.method} ${request.url.encodedPath} -> ${response.code}" +
                    " in ${System.currentTimeMillis() - started}ms"
            )
            response
        }
        .build()

    /**
     * For calls that are slow because of what they do, not because something is
     * wrong.
     *
     * Interactive search asks every configured indexer in series and answers in
     * seconds -- 3.8s here with a single indexer, and proportionally longer with
     * more. Under the 45s ceiling the normal client imposes, a stack with a
     * dozen indexers would simply fail, and the fix cannot be raising that
     * ceiling everywhere: 45s is what stops a dead hub hanging a screen.
     */
    private val slowApi: OkHttpClient = api.newBuilder()
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val offlineHttp: OkHttpClient = api.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 2
            maxRequestsPerHost = 2
        })
        .cache(null)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Shares the pool, owns its own dispatcher, and skips the JSON cache. */
    val imageHttp: OkHttpClient = api.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        .cache(null)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .okHttpClient(imageHttp)
            .crossfade(180)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun base(): String = HubSettings.baseUrl(context)

    override fun imageUrl(hubPath: String): String =
        if (hubPath.isEmpty()) "" else HubEndpoints.image(base(), hubPath)

    override fun playbackUrl(hubPath: String): String =
        if (hubPath.isEmpty()) "" else HubEndpoints.playbackResource(base(), hubPath)

    override suspend fun playbackBytes(hubPath: String): HubResult<ByteArray> {
        if (base().isEmpty() || hubPath.isEmpty()) {
            return HubResult.Failed(FailureKind.UNAUTHORIZED, "No playback resource configured")
        }
        return try {
            withContext(Dispatchers.IO) {
                api.newCall(
                    Request.Builder()
                        .url(playbackUrl(hubPath))
                        .cacheControl(noStore)
                        .build()
                ).await().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val kind = HubFailures.classify(null, response.code)
                        return@withContext HubResult.Failed(
                            kind,
                            hubMessage(body) ?: "Subtitle file could not be loaded"
                        )
                    }
                    val body = response.body
                        ?: return@withContext HubResult.Failed(
                            FailureKind.BAD_RESPONSE,
                            "The subtitle file was empty"
                        )
                    if (body.contentLength() > MAX_PLAYBACK_TEXT_BYTES) {
                        return@withContext HubResult.Failed(
                            FailureKind.BAD_RESPONSE,
                            "The subtitle file is too large"
                        )
                    }
                    val output = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val chunk = ByteArray(8 * 1024)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (output.size() + count > MAX_PLAYBACK_TEXT_BYTES) {
                                return@withContext HubResult.Failed(
                                    FailureKind.BAD_RESPONSE,
                                    "The subtitle file is too large"
                                )
                            }
                            output.write(chunk, 0, count)
                        }
                    }
                    HubResult.Ok(output.toByteArray())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("net", "subtitle bytes failed ${e.javaClass.name}: ${e.message?.take(160)}")
            val kind = HubFailures.classify(e.javaClass.name, null)
            HubResult.Failed(kind, "Subtitle file could not be loaded")
        }
    }

    override suspend fun health(): HubResult<HealthResponse> =
        get(HubEndpoints.health(base())) { json.decodeFromString<HealthResponse>(it) }

    override suspend fun scanJellyfinLibrary(): HubResult<ActionAck> =
        mutate(HubEndpoints.scanJellyfinLibrary(base()))

    override suspend fun users(): HubResult<UsersResponse> =
        get(HubEndpoints.users(base()), noCache = true) { json.decodeFromString<UsersResponse>(it) }

    override suspend fun home(): HubResult<HomeResponse> =
        get(HubEndpoints.home(base()), noCache = true) { json.decodeFromString<HomeResponse>(it) }

    override suspend fun library(): HubResult<LibraryResponse> =
        get(HubEndpoints.library(base())) { json.decodeFromString<LibraryResponse>(it) }

    override suspend fun libraryItems(
        viewId: String,
        page: Int,
        sort: String,
        order: String
    ): HubResult<LibraryItemsResponse> =
        get(HubEndpoints.libraryItems(base(), viewId, page, sort, order)) {
            json.decodeFromString<LibraryItemsResponse>(it)
        }

    override suspend fun libraryItem(itemId: String): HubResult<LibraryItemResponse> =
        get(HubEndpoints.libraryItem(base(), itemId), noCache = true) {
            json.decodeFromString<LibraryItemResponse>(it)
        }

    override suspend fun librarySeasons(seriesId: String): HubResult<LibrarySeasonsResponse> =
        get(HubEndpoints.librarySeasons(base(), seriesId), noCache = true) {
            json.decodeFromString<LibrarySeasonsResponse>(it)
        }

    override suspend fun libraryEpisodes(
        seriesId: String,
        seasonId: String,
        page: Int
    ): HubResult<LibraryEpisodesResponse> =
        get(HubEndpoints.libraryEpisodes(base(), seriesId, seasonId, page), noCache = true) {
            json.decodeFromString<LibraryEpisodesResponse>(it)
        }

    override suspend fun librarySearch(query: String, page: Int): HubResult<LibraryItemsResponse> =
        get(HubEndpoints.librarySearch(base(), query, page), noCache = true) {
            json.decodeFromString<LibraryItemsResponse>(it)
        }

    override suspend fun libraryFavorites(page: Int): HubResult<LibraryItemsResponse> =
        get(HubEndpoints.libraryFavorites(base(), page), noCache = true) {
            json.decodeFromString<LibraryItemsResponse>(it)
        }

    override suspend fun updateLibraryState(
        itemId: String,
        state: LibraryStateRequest
    ): HubResult<LibraryItemResponse> = postOnce(
        HubEndpoints.libraryState(base(), itemId),
        json.encodeToString(LibraryStateRequest.serializer(), state)
    ) { json.decodeFromString<LibraryItemResponse>(it) }

    override suspend fun seriesPlayTarget(seriesId: String): HubResult<SeriesPlayTargetResponse> =
        get(HubEndpoints.seriesPlayTarget(base(), seriesId), noCache = true) {
            json.decodeFromString<SeriesPlayTargetResponse>(it)
        }

    override suspend fun preparePlayback(
        itemId: String,
        body: PlaybackPrepareBody
    ): HubResult<PlaybackPrepareResponse> = postOnce(
        HubEndpoints.preparePlayback(base(), itemId),
        json.encodeToString(PlaybackPrepareBody.serializer(), body)
    ) { json.decodeFromString<PlaybackPrepareResponse>(it) }

    override suspend fun selectPlayback(
        sessionId: String,
        body: PlaybackSelectBody,
        userId: String
    ): HubResult<PlaybackPrepareResponse> = postOnce(
        HubEndpoints.selectPlayback(base(), sessionId),
        json.encodeToString(PlaybackSelectBody.serializer(), body),
        userId = userId
    ) { json.decodeFromString<PlaybackPrepareResponse>(it) }

    override suspend fun playbackEvent(
        sessionId: String,
        body: PlaybackEventBody,
        userId: String
    ): HubResult<ActionAck> = postOnce(
        HubEndpoints.playbackEvent(base(), sessionId),
        json.encodeToString(PlaybackEventBody.serializer(), body),
        userId = userId
    ) { json.decodeFromString<ActionAck>(it) }

    override suspend fun deletePlayback(sessionId: String, userId: String): HubResult<ActionAck> =
        mutate(HubEndpoints.deletePlayback(base(), sessionId), userId)

    override suspend fun offlineSelection(seriesId: String): HubResult<OfflineSelectionResponse> =
        get(HubEndpoints.offlineSelection(base(), seriesId), noCache = true) {
            json.decodeFromString<OfflineSelectionResponse>(it)
        }

    override suspend fun prepareOffline(body: OfflinePrepareBody): HubResult<OfflinePrepareResponse> =
        postOnce(
            HubEndpoints.prepareOffline(base()),
            json.encodeToString(OfflinePrepareBody.serializer(), body)
        ) { json.decodeFromString<OfflinePrepareResponse>(it) }

    override suspend fun renewOffline(grantId: String): HubResult<OfflineManifest> = postOnce(
        HubEndpoints.renewOffline(base(), grantId), "{}"
    ) { json.decodeFromString<OfflineManifest>(it) }

    override suspend fun syncOfflineProgress(
        body: OfflineProgressSyncBody
    ): HubResult<OfflineProgressSyncResponse> = postOnce(
        HubEndpoints.syncOfflineProgress(base()),
        json.encodeToString(OfflineProgressSyncBody.serializer(), body)
    ) { json.decodeFromString<OfflineProgressSyncResponse>(it) }

    /** A long-running, uncached client for resumable media transfers. */
    fun offlineDownloadCall(hubPath: String, downloadedBytes: Long): Call {
        val builder = Request.Builder()
            .url(playbackUrl(hubPath))
            .cacheControl(noStore)
        if (downloadedBytes > 0) builder.header("Range", "bytes=$downloadedBytes-")
        return offlineHttp.newCall(builder.build())
    }

    override suspend fun search(query: String, page: Int): HubResult<SearchResponse> =
        get(HubEndpoints.search(base(), query, page)) {
            json.decodeFromString<SearchResponse>(it)
        }

    override suspend fun mediaDetail(key: String): HubResult<MediaDetail> =
        get(HubEndpoints.mediaDetail(base(), key)) { json.decodeFromString<MediaDetail>(it) }

    override suspend fun person(id: Int, sort: String): HubResult<PersonResponse> =
        get(HubEndpoints.person(base(), id, sort)) { json.decodeFromString<PersonResponse>(it) }

    /**
     * The download queue.
     *
     * Never served from the HTTP cache. A torrent list from two minutes ago is
     * not slightly out of date, it is wrong -- it shows finished transfers as
     * running and misses the one that just failed. The hub caps its own cache
     * at three seconds for the same reason; this is the client half of it.
     */
    override suspend fun activity(includeFinished: Boolean): HubResult<ActivityResponse> =
        get(HubEndpoints.activity(base(), includeFinished), noCache = true) {
            json.decodeFromString<ActivityResponse>(it)
        }

    override suspend fun notifications(limits: NotificationLimits): HubResult<NotificationsResponse> =
        get(HubEndpoints.notifications(base(), limits), noCache = true) {
            json.decodeFromString<NotificationsResponse>(it)
        }

    override suspend fun downloadAction(id: String, action: String): HubResult<ActionAck> =
        mutate(HubEndpoints.downloadAction(base(), id, action))

    override suspend fun deleteDownload(id: String, deleteFiles: Boolean): HubResult<ActionAck> =
        mutate(HubEndpoints.downloadDelete(base(), id, deleteFiles))

    override suspend fun removeFromQueue(
        service: String,
        queueId: Int,
        removeFromClient: Boolean,
        blocklist: Boolean,
        search: Boolean
    ): HubResult<ActionAck> = mutate(
        HubEndpoints.queueRemove(base(), service, queueId, removeFromClient, blocklist, search)
    )

    /**
     * One mutating call, attempted exactly once.
     *
     * Same reasoning as [requestMedia] and it matters more here: a timeout does
     * not mean the delete did not happen, and a retried "remove and blocklist"
     * that actually succeeded the first time would blocklist a second release.
     */
    private suspend fun mutate(request: HubRequest, userId: String = ""): HubResult<ActionAck> {
        if (base().isEmpty()) {
            return HubResult.Failed(FailureKind.UNAUTHORIZED, "No hub configured")
        }
        return try {
            withContext(Dispatchers.IO) {
                val builder = Request.Builder().url(request.url).cacheControl(noStore).apply {
                    if (userId.isNotEmpty()) header(JELLYFIN_USER_HEADER, userId)
                }
                when (request.method) {
                    "POST" -> builder.post(EMPTY_BODY)
                    "DELETE" -> builder.delete()
                    else -> builder.get()
                }
                api.newCall(builder.build()).await().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        HubResult.Ok(json.decodeFromString<ActionAck>(body))
                    } else {
                        val kind = HubFailures.classify(null, response.code)
                        HubResult.Failed(kind, hubMessage(body) ?: HubFailures.message(kind))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("net", "mutate failed ${e.javaClass.name}: ${e.message?.take(160)}")
            HubResult.Failed(HubFailures.classify(e.javaClass.name, null))
        }
    }

    /**
     * Submitting a request is the one call that is never retried.
     *
     * A timeout does not mean it did not happen. Retrying one that actually
     * succeeded leaves a duplicate for someone to unpick by hand, which is
     * worse than making the user press the button again.
     */
    override suspend fun discover(): HubResult<DiscoverResponse> =
        get(HubEndpoints.discover(base())) { json.decodeFromString<DiscoverResponse>(it) }

    override suspend fun discoverRow(row: String, page: Int): HubResult<DiscoverResponse> =
        get(HubEndpoints.discoverRow(base(), row, page)) {
            json.decodeFromString<DiscoverResponse>(it)
        }

    override suspend fun requestOptions(key: String): HubResult<RequestOptions> =
        get(HubEndpoints.requestOptions(base(), key)) {
            json.decodeFromString<RequestOptions>(it)
        }

    /**
     * Interactive search.
     *
     * Given its own long deadline: this asks every configured indexer and takes
     * seconds rather than milliseconds. Measured against the real stack at 3.8s
     * with one indexer, and it scales with however many are configured.
     */
    override suspend fun releaseTargets(key: String, season: Int): HubResult<ReleaseTargetsResponse> =
        get(HubEndpoints.releaseTargets(base(), key, season), noCache = true) {
            json.decodeFromString<ReleaseTargetsResponse>(it)
        }

    override suspend fun releases(key: String, season: Int, episode: Int): HubResult<ReleasesResponse> =
        get(HubEndpoints.releases(base(), key, season, episode), noCache = true, slow = true) {
            json.decodeFromString<ReleasesResponse>(it)
        }

    /**
     * Grab one chosen release. Never retried, for the same reason as
     * [requestMedia]: a timeout does not mean it did not happen, and a repeat
     * would start the same download twice.
     */
    override suspend fun grab(
        key: String,
        releaseId: String,
        season: Int,
        episode: Int
    ): HubResult<GrabResponse> = postOnce(
        HubEndpoints.grab(base(), key),
        json.encodeToString(GrabBody.serializer(), GrabBody(releaseId, season, episode)),
        slow = true
    ) { json.decodeFromString<GrabResponse>(it) }

    override suspend fun requestMedia(
        key: String,
        profileId: Int?,
        rootFolder: String?,
        serverId: Int?,
        seasons: kotlinx.serialization.json.JsonElement?
    ): HubResult<CreateRequestResponse> {
        if (base().isEmpty()) {
            return HubResult.Failed(FailureKind.UNAUTHORIZED, "No hub configured")
        }
        val payload = json.encodeToString(
            CreateRequestBody.serializer(),
            CreateRequestBody(key, seasons, profileId, rootFolder, serverId)
        )
        return try {
            withContext(Dispatchers.IO) {
                val call = api.newCall(
                    Request.Builder()
                        .url(HubEndpoints.requests(base()).url)
                        .post(payload.toRequestBody(jsonMedia))
                        .build()
                )
                call.await().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        HubResult.Ok(
                            json.decodeFromString<CreateRequestResponse>(body)
                        )
                    } else {
                        // The hub distinguishes "already requested" from "over
                        // quota" from "blocklisted", and each deserves better
                        // than a generic failure message.
                        HubResult.Failed(
                            HubFailures.classify(null, response.code),
                            hubMessage(body) ?: HubFailures.message(
                                HubFailures.classify(null, response.code)
                            )
                        )
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("net", "request failed ${e.javaClass.name}: ${e.message?.take(160)}")
            HubResult.Failed(HubFailures.classify(e.javaClass.name, null))
        }
    }

    /**
     * One POST with a JSON body, attempted exactly once.
     *
     * The no-retry rule is the same one [requestMedia] documents, and it
     * matters just as much for a grab: a timeout does not mean the release was
     * not sent, and repeating it starts the same download a second time.
     */
    private suspend fun <T> postOnce(
        request: HubRequest,
        payload: String,
        slow: Boolean = false,
        userId: String = "",
        decode: (String) -> T
    ): HubResult<T> {
        if (base().isEmpty()) {
            return HubResult.Failed(FailureKind.UNAUTHORIZED, "No hub configured")
        }
        return try {
            withContext(Dispatchers.IO) {
                val client = if (slow) slowApi else api
                val call = client.newCall(
                    Request.Builder()
                        .url(request.url)
                        .cacheControl(noStore)
                        .apply { if (userId.isNotEmpty()) header(JELLYFIN_USER_HEADER, userId) }
                        .post(payload.toRequestBody(jsonMedia))
                        .build()
                )
                call.await().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        HubResult.Ok(decode(body))
                    } else {
                        val kind = HubFailures.classify(null, response.code)
                        HubResult.Failed(kind, hubMessage(body) ?: HubFailures.message(kind))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("net", "post failed ${e.javaClass.name}: ${e.message?.take(160)}")
            HubResult.Failed(HubFailures.classify(e.javaClass.name, null))
        }
    }

    private val noStore = okhttp3.CacheControl.Builder().noStore().noCache().build()

    /** Pulls the hub's own wording out of an error envelope, if it sent one. */
    private fun hubMessage(body: String): String? = try {
        json.decodeFromString<HubErrorBody>(body).error.message.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> get(
        request: HubRequest,
        noCache: Boolean = false,
        slow: Boolean = false,
        decode: (String) -> T
    ): HubResult<T> {
        if (base().isEmpty()) {
            return HubResult.Failed(FailureKind.UNAUTHORIZED, "No hub configured")
        }

        var attempt = 1
        while (true) {
            val outcome = attemptOnce(request, noCache, slow, decode)
            if (outcome is HubResult.Ok) return outcome

            val failure = outcome as HubResult.Failed
            val delay = RetryPolicy.delayMsFor(attempt, failure.kind, idempotent = true)
                ?: return failure
            DebugLog.log("net", "retry ${attempt + 1} in ${delay}ms after ${failure.kind}")
            kotlinx.coroutines.delay(delay)
            attempt++
        }
    }

    private suspend fun <T> attemptOnce(
        request: HubRequest,
        noCache: Boolean,
        slow: Boolean,
        decode: (String) -> T
    ): HubResult<T> = try {
        // The whole call runs off the main thread. await() resumes on whatever
        // dispatcher the caller is on -- which is Main.immediate for a screen --
        // and Response.body.string() is a *blocking* read, so doing it there
        // throws NetworkOnMainThreadException. Enqueuing the call asynchronously
        // is not enough on its own; reading the body is the part that blocks.
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(request.url)
            if (noCache) builder.cacheControl(noStore)
            val client = if (slow) slowApi else api
            val response = client.newCall(builder.build()).await()
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    // The hub's own sentence, when it sent one. It says things
                    // like "This series is not in sonarr yet -- request it
                    // first, then pick a release", and the generic "the hub
                    // sent something unexpected" threw that away and left the
                    // user with no idea what to do.
                    val kind = HubFailures.classify(null, it.code)
                    HubResult.Failed(kind, hubMessage(body) ?: HubFailures.message(kind))
                } else {
                    HubResult.Ok(decode(body))
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Never swallowed. Superseding a search cancels the previous one on
        // purpose, and turning that into a "failure" both reports a bug that is
        // not one and breaks structured concurrency for everything upstream.
        throw e
    } catch (e: Exception) {
        // The class name and message go to the trace: a decode failure that
        // classifies as UNKNOWN is otherwise indistinguishable from a network
        // fault, and they need completely different fixes.
        DebugLog.log("net", "exception ${e.javaClass.name}: ${e.message?.take(200)}")
        HubResult.Failed(HubFailures.classify(e.javaClass.name, null))
    }
}

/** A POST with no body still needs one; OkHttp will not send a null. */
private val EMPTY_BODY = ByteArray(0).toRequestBody(null, 0, 0)
private const val JELLYFIN_USER_HEADER = "X-Jellyfin-User"
private const val MAX_PLAYBACK_TEXT_BYTES = 8 * 1024 * 1024

/**
 * Bridges OkHttp to coroutines.
 *
 * The cancellation handler is the whole point: without it, backing out of a
 * screen cancels the coroutine but leaves the socket open and the response
 * arriving for nobody. This cancels the call itself.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
}
