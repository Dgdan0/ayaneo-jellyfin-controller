package com.pocketds.hub.net

import android.content.Context
import coil.ImageLoader
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.ActionAck
import com.pocketds.hub.model.ActivityResponse
import com.pocketds.hub.model.CreateRequestBody
import com.pocketds.hub.model.CacheInfo
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
import com.pocketds.hub.model.ReadingDiscoverResponse
import com.pocketds.hub.model.ReadingSearchResponse
import com.pocketds.hub.model.ReadingLibrariesResponse
import com.pocketds.hub.model.ReadingLibraryItemsResponse
import com.pocketds.hub.model.ReadingAuthorsResponse
import com.pocketds.hub.model.ReadingResolveResponse
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingPublicationManifest
import com.pocketds.hub.model.ReadingPublicationProgressBody
import com.pocketds.hub.model.EpubPositionBody
import com.pocketds.hub.model.EpubPositionResponse
import com.pocketds.hub.model.ReadingCreateRequestBody
import com.pocketds.hub.model.ReadingDownloadsResponse
import com.pocketds.hub.model.ReadingRequestOptions
import com.pocketds.hub.model.ReadingRequestResponse
import com.pocketds.hub.model.ReadingReleasesResponse
import com.pocketds.hub.model.ReadingReleaseGrabBody
import com.pocketds.hub.model.ReadingSeriesPreviewResponse
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
import com.pocketds.hub.model.PlaybackCastGrantResponse
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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Everything the app knows how to ask the hub.
 *
 * An interface so the whole UI can be built and driven on the device against
 * [FakeHubApi] before the hub exists or while it is being changed -- which is
 * what let phases A1 and A3 proceed independently of the Go work.
 */
interface HubApi {
    suspend fun removalPreview(kind: String, id: String): HubResult<com.pocketds.hub.model.MediaRemovalPreview> = HubResult.Failed(FailureKind.UNKNOWN, "Server deletion is unavailable")
    suspend fun removeMedia(ticket: String): HubResult<ActionAck> = HubResult.Failed(FailureKind.UNKNOWN, "Server deletion is unavailable")
    suspend fun serverMonitor(): HubResult<com.pocketds.hub.model.ServerMonitor>
    suspend fun subtitles(itemId: String): HubResult<com.pocketds.hub.model.SubtitleState>
    suspend fun searchSubtitles(itemId: String): HubResult<com.pocketds.hub.model.SubtitleSearch>
    suspend fun downloadSubtitle(itemId: String, ticket: String): HubResult<ActionAck>
    suspend fun refreshSubtitles(itemId: String): HubResult<ActionAck>
    suspend fun calendar(start: String, end: String, timezone: String): HubResult<com.pocketds.hub.model.CalendarResponse>
    suspend fun health(): HubResult<HealthResponse>
    suspend fun scanJellyfinLibrary(): HubResult<ActionAck>
    suspend fun scanReadingLibrary(service: String): HubResult<ActionAck>
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
    /** Jellyfin's "more like this". Absent on a hub from before 2026-10, which answers 404. */
    suspend fun librarySimilar(itemId: String): HubResult<LibraryItemsResponse> =
        HubResult.Failed(FailureKind.UNKNOWN, "More like this is unavailable")
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
    suspend fun castGrant(sessionId: String, userId: String = ""): HubResult<PlaybackCastGrantResponse>
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
    suspend fun discover(force: Boolean = false): HubResult<DiscoverResponse>
    suspend fun discoverRow(row: String, page: Int): HubResult<DiscoverResponse>
    suspend fun readingDiscover(type: String, force: Boolean = false): HubResult<ReadingDiscoverResponse>
    suspend fun readingDiscoverRow(
        row: String,
        type: String,
        page: Int
    ): HubResult<ReadingDiscoverResponse>
    suspend fun readingSearch(query: String, type: String): HubResult<ReadingSearchResponse>
    suspend fun readingLibraries(): HubResult<ReadingLibrariesResponse>
    suspend fun serverReadingLists(): HubResult<com.pocketds.hub.model.ServerReadingListsResponse> = HubResult.Failed(FailureKind.UNKNOWN, "Server reading lists are unavailable")
    suspend fun serverReadingList(id: Int): HubResult<com.pocketds.hub.model.ServerReadingListResponse> = HubResult.Failed(FailureKind.UNKNOWN, "Server reading list is unavailable")
    suspend fun readingLibraryItems(
        libraryId: String,
        page: Int = 1,
        sort: String = "title",
        direction: String = "asc",
        view: String = ""
    ): HubResult<ReadingLibraryItemsResponse>
    suspend fun readingAuthors(libraryId:String,page:Int=1,direction:String="asc",authorId:String=""):HubResult<ReadingAuthorsResponse> =
        HubResult.Failed(FailureKind.UNKNOWN,"Author shelves are unavailable")
    suspend fun readingResolve(source:String,sourceId:String,isbn:String):HubResult<ReadingResolveResponse> =
        HubResult.Failed(FailureKind.UNKNOWN,"Library lookup is unavailable")
    suspend fun readingWork(workId: String): HubResult<ReadingWork>
    suspend fun readingPublication(
        workId: String,
        sourceItemId: String
    ): HubResult<ReadingPublicationManifest>
    fun readingPublicationPageUrl(workId: String, sourceItemId: String, pageIndex: Int): String
    suspend fun saveReadingPublicationProgress(
        workId: String,
        sourceItemId: String,
        pageIndex: Int
    ): HubResult<ActionAck>
    suspend fun saveReadingPublicationCheckpoint(workId: String, sourceItemId: String, body: ReadingPublicationProgressBody): HubResult<ActionAck> =
        HubResult.Failed(FailureKind.UNKNOWN, "Conditional reading progress is unavailable")
    suspend fun readingEpubPosition(
        workId: String,
        sourceItemId: String
    ): HubResult<EpubPositionResponse> =
        HubResult.Failed(FailureKind.UNKNOWN, "EPUB position loading is unavailable")
    suspend fun saveReadingEpubPosition(
        workId: String,
        sourceItemId: String,
        body: EpubPositionBody
    ): HubResult<ActionAck> =
        HubResult.Failed(FailureKind.UNKNOWN, "EPUB position saving is unavailable")
    suspend fun downloadReadingEpub(
        workId: String,
        sourceItemId: String,
        destination: File,
        readAlong: Boolean = false
    ): HubResult<ReadingEpubDownload> =
        HubResult.Failed(FailureKind.UNKNOWN, "EPUB downloading is unavailable")
    suspend fun downloadReadingAudiobook(
        workId: String, sourceItemId: String, destination: File
    ): HubResult<ReadingEpubDownload> =
        HubResult.Failed(FailureKind.UNKNOWN, "Audiobook downloading is unavailable")
    suspend fun readingRequestOptions(key: String): HubResult<ReadingRequestOptions>
    suspend fun readingSeriesPreview(key: String): HubResult<ReadingSeriesPreviewResponse>
    suspend fun requestReading(body: ReadingCreateRequestBody): HubResult<ReadingRequestResponse>
    suspend fun readingReleases(seriesId: Int, search: Boolean): HubResult<ReadingReleasesResponse> =
        HubResult.Failed(FailureKind.UNKNOWN, "Reading releases are unavailable")
    suspend fun grabReadingRelease(seriesId: Int, releaseId: String): HubResult<ActionAck> =
        HubResult.Failed(FailureKind.UNKNOWN, "Reading release selection is unavailable")
    suspend fun readingDownloads(): HubResult<ReadingDownloadsResponse>
    suspend fun retryReadingDownload(id: String): HubResult<ActionAck>
    suspend fun cancelReadingDownload(id: String): HubResult<ActionAck>
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
    suspend fun bandwidth(): HubResult<com.pocketds.hub.model.BandwidthState>
    suspend fun setBandwidth(change: com.pocketds.hub.model.BandwidthChange): HubResult<com.pocketds.hub.model.BandwidthState>
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
class HubConnection(val baseUrl: String, val token: String, val userId: String) {
    companion object {
        fun capture(context: Context) = HubConnection(HubSettings.baseUrl(context), HubSettings.token(context), HubSettings.userId(context))
    }
}

class HubClient(private val context: Context, private val connection: HubConnection? = null) : HubApi {

    companion object {
        /** Process-wide, so a rejected token or a ban stops every client at once. */
        private val gate = CredentialGate()
        private val probeLock = Any()
        @Volatile private var shared: HubClient? = null

        /**
         * The one client for the settings-backed connection. The activity and
         * each service used to build their own: separate token memories (so the
         * download queue kept sending a token the screens had already found
         * rejected) and two OkHttp caches over one `hub-http` directory, which
         * OkHttp does not support.
         */
        fun shared(context: Context): HubClient = shared ?: synchronized(this) {
            shared ?: HubClient(context.applicationContext).also { shared = it }
        }
    }

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
        .cache(if (connection == null) okhttp3.Cache(File(context.cacheDir, "hub-http"), 256L * 1024 * 1024) else null)
        .addInterceptor { chain -> authorize(chain) }
        .build()

    /**
     * Every request -- JSON, images, reader pages, offline transfers -- passes
     * through here, so this is the one place the [CredentialGate] is applied.
     */
    private fun authorize(chain: okhttp3.Interceptor.Chain): Response {
        val token = connection?.token ?: HubSettings.token(context)
        gate.blockFor(token, System.currentTimeMillis())?.let { return refused(chain.request(), it) }
        val request = if (token.isEmpty()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .apply {
                    (connection?.userId ?: HubSettings.userId(context)).takeIf {
                        it.isNotEmpty() && chain.request().header(JELLYFIN_USER_HEADER) == null
                    }?.let {
                        header(JELLYFIN_USER_HEADER, it)
                    }
                }
                .build()
        }
        if (!gate.needsProbe(token)) return send(chain, request, token)
        synchronized(probeLock) {
            // Another request may have settled this token while we waited.
            gate.blockFor(token, System.currentTimeMillis())?.let { return refused(request, it) }
            return send(chain, request, token)
        }
    }

    private fun send(chain: okhttp3.Interceptor.Chain, request: Request, token: String): Response {
        val started = System.currentTimeMillis()
        val response = chain.proceed(request)
        gate.observe(token, response.code, response.header("Retry-After")?.toLongOrNull(), System.currentTimeMillis())
        // The Authorization header is deliberately never logged: a typo'd
        // real token would then sit in the trace in clear text.
        DebugLog.log(
            "net",
            "${request.method} ${request.url.encodedPath} -> ${response.code}" +
                " in ${System.currentTimeMillis() - started}ms"
        )
        return response
    }

    /** The hub's own error envelope, answered without touching the network. */
    private fun refused(request: Request, block: CredentialGate.Block): Response {
        val body = json.encodeToString(
            HubErrorBody.serializer(),
            HubErrorBody(com.pocketds.hub.model.HubErrorDetail(code = "refused_on_device", message = block.message))
        )
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (block is CredentialGate.Block.Banned) 429 else 401)
            .message(block.message)
            .apply { if (block is CredentialGate.Block.Banned) header("Retry-After", "${block.remainingSeconds}") }
            .body(body.toResponseBody(jsonMedia))
            .build()
    }

    private val discoverCache = PersistentResponseCache(File(context.cacheDir, "discover-responses"))

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

    /** Long transfers use their own HTTP/1.1 pool instead of sharing interactive HTTP/2 streams. */
    private val offlineHttp: OkHttpClient = api.newBuilder()
        .connectionPool(ConnectionPool())
        .protocols(listOf(Protocol.HTTP_1_1))
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

    /** Reader archives can be cold-extracted by Kavita and pages can be large. */
    val readerHttp: OkHttpClient = api.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 3
            maxRequestsPerHost = 3
        })
        .cache(null)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .okHttpClient(imageHttp)
            .crossfade(180)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun base(): String = connection?.baseUrl ?: HubSettings.baseUrl(context)

    private fun connectionFailure(): HubResult.Failed? {
        if (base().isEmpty()) {
            return HubResult.Failed(FailureKind.UNAUTHORIZED, "No Hub address is configured")
        }
        val token = connection?.token ?: HubSettings.token(context)
        if (token.isEmpty()) {
            return HubResult.Failed(
                FailureKind.UNAUTHORIZED,
                "No Hub access token — open Manage > Ayaneo Hub"
            )
        }
        return when (val block = gate.blockFor(token, System.currentTimeMillis())) {
            null -> null
            is CredentialGate.Block.Banned -> HubResult.Failed(FailureKind.BANNED, block.message)
            else -> HubResult.Failed(FailureKind.UNAUTHORIZED, block.message)
        }
    }

    /** Why nothing can be sent to the hub right now, for work that waits instead of failing. */
    fun credentialProblem(): String? = connectionFailure()?.message

    override fun imageUrl(hubPath: String): String =
        if (hubPath.isEmpty()) "" else HubEndpoints.image(base(), hubPath)

    override fun playbackUrl(hubPath: String): String =
        if (hubPath.isEmpty()) "" else HubEndpoints.playbackResource(base(), hubPath)

    override suspend fun playbackBytes(hubPath: String): HubResult<ByteArray> {
        connectionFailure()?.let { return it }
        if (hubPath.isEmpty()) {
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

    override suspend fun scanReadingLibrary(service: String): HubResult<ActionAck> =
        mutate(HubEndpoints.scanReadingLibrary(base(), service), slow = true)

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

    override suspend fun librarySimilar(itemId: String): HubResult<LibraryItemsResponse> =
        get(HubEndpoints.librarySimilar(base(), itemId)) {
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

    override suspend fun castGrant(sessionId: String, userId: String): HubResult<PlaybackCastGrantResponse> =
        postOnce(HubEndpoints.castGrant(base(), sessionId), "{}", userId = userId) {
            json.decodeFromString<PlaybackCastGrantResponse>(it)
        }

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
    fun offlineDownloadCall(hubPath: String, downloadedBytes: Long, usePrivateRoute: Boolean = false): Call {
        // The configured public Hub address can be reached outside Android's
        // per-app tailnet VPN path. Keep the ordinary Hub address for browsing,
        // and fall back to it when no public address has been configured.
        val downloadBase = if (usePrivateRoute) base()
            else connection?.baseUrl ?: HubSettings.castBaseUrl(context)
        val builder = Request.Builder()
            .url(HubEndpoints.playbackResource(downloadBase, hubPath))
            .cacheControl(noStore)
        if (downloadedBytes > 0) builder.header("Range", "bytes=$downloadedBytes-")
        return offlineHttp.newCall(builder.build())
    }

    override suspend fun search(query: String, page: Int): HubResult<SearchResponse> =
        get(HubEndpoints.search(base(), query, page)) {
            json.decodeFromString<SearchResponse>(it)
        }

    override suspend fun calendar(start: String, end: String, timezone: String): HubResult<com.pocketds.hub.model.CalendarResponse> =
        get(HubEndpoints.calendar(base(), start, end, timezone), noCache = true) {
            json.decodeFromString<com.pocketds.hub.model.CalendarResponse>(it)
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

    override suspend fun bandwidth(): HubResult<com.pocketds.hub.model.BandwidthState> =
        get(HubEndpoints.bandwidth(base()), noCache = true) { json.decodeFromString<com.pocketds.hub.model.BandwidthState>(it) }

    override suspend fun serverMonitor(): HubResult<com.pocketds.hub.model.ServerMonitor> =
        get(HubRequest(base().trimEnd('/')+"/v1/manage/monitor"),noCache=true) {json.decodeFromString<com.pocketds.hub.model.ServerMonitor>(it)}

    override suspend fun subtitles(itemId: String): HubResult<com.pocketds.hub.model.SubtitleState> =
        get(HubEndpoints.subtitles(base(),itemId),noCache=true) {json.decodeFromString<com.pocketds.hub.model.SubtitleState>(it)}
    override suspend fun refreshSubtitles(itemId: String): HubResult<ActionAck> =
        postOnce(HubEndpoints.refreshSubtitles(base(),itemId),"{}") {json.decodeFromString<ActionAck>(it)}
    override suspend fun searchSubtitles(itemId: String): HubResult<com.pocketds.hub.model.SubtitleSearch> =
        postOnce(HubEndpoints.subtitles(base(),itemId,"search"),"{}",slow=true) {json.decodeFromString<com.pocketds.hub.model.SubtitleSearch>(it)}
    override suspend fun downloadSubtitle(itemId: String,ticket: String): HubResult<ActionAck> =
        postOnce(HubEndpoints.subtitles(base(),itemId,"download"),json.encodeToString(com.pocketds.hub.model.SubtitleDownload.serializer(),com.pocketds.hub.model.SubtitleDownload(ticket)),slow=true) {json.decodeFromString<ActionAck>(it)}

    override suspend fun setBandwidth(change: com.pocketds.hub.model.BandwidthChange): HubResult<com.pocketds.hub.model.BandwidthState> =
        postOnce(HubEndpoints.bandwidth(base()), json.encodeToString(com.pocketds.hub.model.BandwidthChange.serializer(), change)) {
            json.decodeFromString<com.pocketds.hub.model.BandwidthState>(it)
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
    private suspend fun mutate(
        request: HubRequest,
        userId: String = "",
        slow: Boolean = false
    ): HubResult<ActionAck> {
        connectionFailure()?.let { return it }
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
                (if (slow) slowApi else api).newCall(builder.build()).await().use { response ->
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
    override suspend fun discover(force: Boolean): HubResult<DiscoverResponse> =
        persistentDiscoverGet(
            request = HubEndpoints.discover(base()),
            force = force,
            decode = { json.decodeFromString<DiscoverResponse>(it) },
            markCached = { value, age, degraded -> value.copy(cache = localCacheInfo(value.cache, age, degraded)) },
            shouldStore = { it.rows.isNotEmpty() && it.partial.isEmpty() }
        )

    override suspend fun discoverRow(row: String, page: Int): HubResult<DiscoverResponse> =
        persistentDiscoverGet(
            request = HubEndpoints.discoverRow(base(), row, page),
            decode = { json.decodeFromString<DiscoverResponse>(it) },
            markCached = { value, age, degraded -> value.copy(cache = localCacheInfo(value.cache, age, degraded)) },
            shouldStore = { it.rows.isNotEmpty() && it.partial.isEmpty() }
        )

    override suspend fun readingDiscover(type: String, force: Boolean): HubResult<ReadingDiscoverResponse> =
        persistentDiscoverGet(
            request = HubEndpoints.readingDiscover(base(), type),
            force = force,
            decode = { json.decodeFromString<ReadingDiscoverResponse>(it) },
            markCached = { value, age, degraded -> value.copy(cache = localCacheInfo(value.cache, age, degraded)) },
            shouldStore = { it.rows.isNotEmpty() && it.partial.isEmpty() }
        )

    override suspend fun readingDiscoverRow(
        row: String,
        type: String,
        page: Int
    ): HubResult<ReadingDiscoverResponse> =
        persistentDiscoverGet(
            request = HubEndpoints.readingDiscoverRow(base(), row, type, page),
            decode = { json.decodeFromString<ReadingDiscoverResponse>(it) },
            markCached = { value, age, degraded -> value.copy(cache = localCacheInfo(value.cache, age, degraded)) },
            shouldStore = { it.rows.isNotEmpty() && it.partial.isEmpty() }
        )

    override suspend fun readingSearch(query: String, type: String): HubResult<ReadingSearchResponse> =
        get(HubEndpoints.readingSearch(base(), query, type)) {
            json.decodeFromString<ReadingSearchResponse>(it)
        }

    override suspend fun readingLibraries(): HubResult<ReadingLibrariesResponse> =
        get(HubEndpoints.readingLibraries(base())) {
            json.decodeFromString<ReadingLibrariesResponse>(it)
        }

    override suspend fun serverReadingLists(): HubResult<com.pocketds.hub.model.ServerReadingListsResponse> =
        get(HubEndpoints.serverReadingLists(base()), noCache = true) { json.decodeFromString<com.pocketds.hub.model.ServerReadingListsResponse>(it) }
    override suspend fun removalPreview(kind: String, id: String): HubResult<com.pocketds.hub.model.MediaRemovalPreview> =
        postOnce(HubEndpoints.removalPreview(base()), json.encodeToString(com.pocketds.hub.model.MediaRemovalRequest.serializer(), com.pocketds.hub.model.MediaRemovalRequest(kind,id)), slow = true) { json.decodeFromString<com.pocketds.hub.model.MediaRemovalPreview>(it) }
    override suspend fun removeMedia(ticket: String): HubResult<ActionAck> {
        val result=postOnce(HubEndpoints.mediaRemove(base()), json.encodeToString(com.pocketds.hub.model.MediaRemovalConfirmation.serializer(), com.pocketds.hub.model.MediaRemovalConfirmation(ticket)), slow = true, retryConnection = false) { json.decodeFromString<ActionAck>(it) }
        withContext(Dispatchers.IO) { runCatching { api.cache?.evictAll() } }
        return result
    }
    override suspend fun serverReadingList(id: Int): HubResult<com.pocketds.hub.model.ServerReadingListResponse> =
        get(HubEndpoints.serverReadingLists(base(), id), noCache = true) { json.decodeFromString<com.pocketds.hub.model.ServerReadingListResponse>(it) }

    override suspend fun readingLibraryItems(
        libraryId: String,
        page: Int,
        sort: String,
        direction: String,
        view: String
    ): HubResult<ReadingLibraryItemsResponse> =
        get(HubEndpoints.readingLibraryItems(base(), libraryId, page, sort, direction, view)) {
            json.decodeFromString<ReadingLibraryItemsResponse>(it)
        }

    override suspend fun readingAuthors(libraryId:String,page:Int,direction:String,authorId:String):HubResult<ReadingAuthorsResponse> =
        get(HubEndpoints.readingAuthors(base(),libraryId,page,direction,authorId)) { json.decodeFromString<ReadingAuthorsResponse>(it) }
    override suspend fun readingResolve(source:String,sourceId:String,isbn:String):HubResult<ReadingResolveResponse> =
        get(HubEndpoints.readingResolve(base(),source,sourceId,isbn),noCache=true) { json.decodeFromString<ReadingResolveResponse>(it) }
    override suspend fun readingWork(workId: String): HubResult<ReadingWork> =
        get(HubEndpoints.readingWork(base(), workId)) {
            json.decodeFromString<ReadingWork>(it)
        }

    override suspend fun readingPublication(
        workId: String,
        sourceItemId: String
    ): HubResult<ReadingPublicationManifest> =
        get(HubEndpoints.readingPublication(base(), workId, sourceItemId), noCache = true, slow = true) {
            json.decodeFromString<ReadingPublicationManifest>(it)
        }

    override fun readingPublicationPageUrl(
        workId: String,
        sourceItemId: String,
        pageIndex: Int
    ): String = HubEndpoints.readingPublicationPage(base(), workId, sourceItemId, pageIndex)

    override suspend fun saveReadingPublicationProgress(
        workId: String,
        sourceItemId: String,
        pageIndex: Int
    ): HubResult<ActionAck> = postOnce(
        HubEndpoints.readingPublicationProgress(base(), workId, sourceItemId),
        json.encodeToString(
            ReadingPublicationProgressBody.serializer(),
            ReadingPublicationProgressBody(pageIndex)
        )
    ) { json.decodeFromString<ActionAck>(it) }

    override suspend fun readingEpubPosition(
        workId: String,
        sourceItemId: String
    ): HubResult<EpubPositionResponse> =
        get(HubEndpoints.readingEpubPosition(base(), workId, sourceItemId), noCache = true) {
            json.decodeFromString<EpubPositionResponse>(it)
        }

    override suspend fun saveReadingPublicationCheckpoint(workId: String, sourceItemId: String, body: ReadingPublicationProgressBody): HubResult<ActionAck> =
        postOnce(HubEndpoints.readingPublicationProgress(base(), workId, sourceItemId),
            json.encodeToString(ReadingPublicationProgressBody.serializer(), body)) { json.decodeFromString<ActionAck>(it) }

    override suspend fun saveReadingEpubPosition(
        workId: String,
        sourceItemId: String,
        body: EpubPositionBody
    ): HubResult<ActionAck> = postOnce(
        HubEndpoints.readingEpubPosition(base(), workId, sourceItemId).copy(method = "POST"),
        json.encodeToString(EpubPositionBody.serializer(), body)
    ) { json.decodeFromString<ActionAck>(it) }

    override suspend fun downloadReadingEpub(
        workId: String,
        sourceItemId: String,
        destination: File,
        readAlong: Boolean
    ): HubResult<ReadingEpubDownload> {
        connectionFailure()?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                destination.parentFile?.mkdirs()
                val request = Request.Builder()
                    .url(HubEndpoints.readingEpubFile(base(), workId, sourceItemId, readAlong))
                    .cacheControl(noStore)
                    .build()
                HubResult.Ok(ResumableEpubTransfer.downloadWithRetry(offlineHttp, request, destination))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: EpubTransferHttpException) {
            HubResult.Failed(HubFailures.classify(null, e.status), hubMessage(e.responseText) ?: "The EPUB could not be downloaded")
        } catch (e: Exception) {
            DebugLog.log("net", "epub download failed ${e.javaClass.name}: ${e.message?.take(160)}")
            HubResult.Failed(HubFailures.classify(e.javaClass.name, null), "The EPUB could not be downloaded")
        }
    }

    override suspend fun downloadReadingAudiobook(
        workId: String, sourceItemId: String, destination: File
    ): HubResult<ReadingEpubDownload> {
        connectionFailure()?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                destination.parentFile?.mkdirs()
                val request = Request.Builder()
                    .url(HubEndpoints.readingAudiobookFile(base(), workId, sourceItemId))
                    .cacheControl(noStore)
                    .build()
                HubResult.Ok(ResumableEpubTransfer.downloadWithRetry(
                    offlineHttp, request, destination, requireEpubManifest = false
                ))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: EpubTransferHttpException) {
            HubResult.Failed(HubFailures.classify(null, e.status), hubMessage(e.responseText) ?: "The audiobook could not be downloaded")
        } catch (e: Exception) {
            DebugLog.log("net", "audiobook download failed ${e.javaClass.name}: ${e.message?.take(160)}")
            HubResult.Failed(HubFailures.classify(e.javaClass.name, null), "The audiobook could not be downloaded")
        }
    }

    override suspend fun readingRequestOptions(key: String): HubResult<ReadingRequestOptions> =
        get(HubEndpoints.readingRequestOptions(base(), key), noCache = true) {
            json.decodeFromString<ReadingRequestOptions>(it)
        }

    override suspend fun readingSeriesPreview(key: String): HubResult<ReadingSeriesPreviewResponse> =
        get(HubEndpoints.readingSeriesPreview(base(), key), noCache = true) {
            json.decodeFromString<ReadingSeriesPreviewResponse>(it)
        }

    override suspend fun requestReading(
        body: ReadingCreateRequestBody
    ): HubResult<ReadingRequestResponse> = postOnce(
        HubEndpoints.readingRequests(base()),
        json.encodeToString(ReadingCreateRequestBody.serializer(), body)
    ) { json.decodeFromString<ReadingRequestResponse>(it) }

    override suspend fun readingReleases(seriesId: Int, search: Boolean): HubResult<ReadingReleasesResponse> =
        if (search) postOnce(HubEndpoints.searchReadingReleases(base(), seriesId), "{}", slow = true) {
            json.decodeFromString<ReadingReleasesResponse>(it)
        } else get(HubEndpoints.readingReleases(base(), seriesId), noCache = true) {
            json.decodeFromString<ReadingReleasesResponse>(it)
        }

    override suspend fun grabReadingRelease(seriesId: Int, releaseId: String): HubResult<ActionAck> =
        postOnce(HubEndpoints.grabReadingRelease(base(), seriesId),
            json.encodeToString(ReadingReleaseGrabBody.serializer(), ReadingReleaseGrabBody(releaseId))) {
            json.decodeFromString<ActionAck>(it)
        }

    override suspend fun readingDownloads(): HubResult<ReadingDownloadsResponse> =
        get(HubEndpoints.readingDownloads(base()), noCache = true) {
            json.decodeFromString<ReadingDownloadsResponse>(it)
        }

    override suspend fun retryReadingDownload(id: String): HubResult<ActionAck> =
        mutate(HubEndpoints.retryReadingDownload(base(), id))

    override suspend fun cancelReadingDownload(id: String): HubResult<ActionAck> =
        mutate(HubEndpoints.cancelReadingDownload(base(), id))

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
        connectionFailure()?.let { return it }
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
        retryConnection: Boolean = true,
        decode: (String) -> T
    ): HubResult<T> {
        connectionFailure()?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                val baseClient = if (slow) slowApi else api
                val client = if(retryConnection) baseClient else baseClient.newBuilder().retryOnConnectionFailure(false).build()
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

    /**
     * Discover is the only screen persisted as JSON on the device. Its rows are
     * slow-changing recommendations, unlike progress, transfers, notifications,
     * and service health where an old answer would actively mislead the user.
     */
    private suspend fun <T> persistentDiscoverGet(
        request: HubRequest,
        force: Boolean = false,
        decode: (String) -> T,
        markCached: (T, Long, Boolean) -> T,
        shouldStore: (T) -> Boolean
    ): HubResult<T> {
        connectionFailure()?.let { return it }
        val key = buildString {
            append("discover-v1\n")
            append(request.url).append('\n')
            append(HubSettings.baseUrl(context)).append('\n')
            append(HubSettings.token(context)).append('\n')
            append(HubSettings.userId(context))
        }

        fun readCached(maxAgeMillis: Long, degraded: Boolean): HubResult<T>? {
            val entry = discoverCache.read(key, maxAgeMillis) ?: return null
            return try {
                HubResult.Ok(markCached(decode(entry.body), entry.ageMillis, degraded))
            } catch (_: Exception) {
                discoverCache.remove(key)
                null
            }
        }

        if (!force) {
            readCached(DISCOVER_FRESH_MILLIS, degraded = false)?.let { return it }
        }

        val network = get(
            request = request,
            noCache = true,
            decode = decode,
            onSuccess = { body, value ->
                if (shouldStore(value)) discoverCache.put(key, body)
            }
        )
        if (network is HubResult.Ok) return network
        return readCached(DISCOVER_FALLBACK_MILLIS, degraded = true) ?: network
    }

    private fun localCacheInfo(upstream: CacheInfo, ageMillis: Long, degraded: Boolean): CacheInfo {
        val localAge = ageMillis / 1_000L
        val totalAge = (upstream.ageSeconds.toLong() + localAge)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return upstream.copy(
            hit = true,
            ageSeconds = totalAge,
            stale = upstream.stale || degraded,
            degraded = upstream.degraded || degraded
        )
    }

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
        onSuccess: ((String, T) -> Unit)? = null,
        decode: (String) -> T
    ): HubResult<T> {
        connectionFailure()?.let { return it }

        var attempt = 1
        while (true) {
            val outcome = attemptOnce(request, noCache, slow, decode, onSuccess)
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
        decode: (String) -> T,
        onSuccess: ((String, T) -> Unit)?
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
                    val value = decode(body)
                    onSuccess?.invoke(body, value)
                    HubResult.Ok(value)
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
private const val DISCOVER_FRESH_MILLIS = 30L * 60L * 1_000L
private const val DISCOVER_FALLBACK_MILLIS = 14L * 24L * 60L * 60L * 1_000L

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
