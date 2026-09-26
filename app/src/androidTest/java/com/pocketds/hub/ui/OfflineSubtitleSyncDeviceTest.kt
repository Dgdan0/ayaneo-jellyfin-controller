package com.pocketds.hub.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.OfflineSource
import com.pocketds.hub.model.OfflineSubtitle
import com.pocketds.hub.model.PlaybackTrack
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.offline.OfflineSubtitleSync
import com.pocketds.hub.offline.PendingSubtitleSync
import com.pocketds.hub.settings.HubSettings
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Hub HTTP client and sidecar storage against a local fixture server. */
@RunWith(AndroidJUnit4::class)
class OfflineSubtitleSyncDeviceTest {
    @Test fun freshSidecarIsSavedWithoutRequestingOrChangingTheVideo() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val item="sync-fixture-${System.nanoTime()}"
        val rowKey="sync-row-${System.nanoTime()}"
        val original=OfflineManifest(grantId="fixture-grant",batchKey=rowKey,clientItemKey=rowKey,
            item=LibraryItem(id=item,type="movie",title="Fixture film"),
            source=OfflineSource(id="source-1",container="mp4",sizeBytes=4))
        val subtitle=OfflineSubtitle(PlaybackTrack(index=5,type="Subtitle",language="heb",codec="srt",external=true),
            "/v1/offline/grants/fixture-grant/subtitles/5")
        val refreshed=original.copy(subtitles=listOf(subtitle))
        val json=Json.encodeToString(refreshed).toByteArray()
        val bytes="1\n00:00:00,000 --> 00:00:01,000\nHello\n".toByteArray()
        val mediaRequests=AtomicInteger()
        val subtitleRequests=AtomicInteger()
        val truncateSubtitle=AtomicBoolean(false)
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1")).apply { soTimeout=500 }
        val thread=Thread {
            while(!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader=socket.getInputStream().bufferedReader()
                        val path=reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
                        var length=0
                        while(true) {
                            val line=reader.readLine() ?: break
                            if(line.isEmpty()) break
                            if(line.startsWith("Content-Length:",true)) length=line.substringAfter(':').trim().toInt()
                        }
                        if(length>0) repeat(length) { reader.read() }
                        val payload=when {
                            path.endsWith("/renew") -> json
                            path.endsWith("/subtitles/5") -> {subtitleRequests.incrementAndGet();bytes}
                            else -> {mediaRequests.incrementAndGet();ByteArray(0)}
                        }
                        val code=if(payload.isEmpty()) "404 Not Found" else "200 OK"
                        val type=if(path.endsWith("/renew")) "application/json" else "text/plain"
                        socket.getOutputStream().apply {
                            val declaredSize=payload.size + if(path.endsWith("/subtitles/5") && truncateSubtitle.get()) 100 else 0
                            write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: $declaredSize\r\nConnection: close\r\n\r\n".toByteArray())
                            write(payload);flush()
                        }
                    }
                } catch (_: SocketTimeoutException) { } catch (_: java.net.SocketException) { break }
            }
        }.apply { start() }
        val oldUrl=HubSettings.baseUrl(context)
        val oldToken=HubSettings.token(context)
        HubSettings.save(context,"http://127.0.0.1:${server.localPort}","fixture-token")
        val repository=OfflineRepository.get(context)
        var rowId=""
        try {
            assertEquals(1,repository.enqueue("Fixture film","",listOf(original)))
            val row=checkNotNull(repository.forItem(item));rowId=row.id
            repository.mediaFile(row).writeText("film")
            repository.finish(row.id)
            val api=HubClient(context)
            val renewal=runBlocking { api.renewOffline("fixture-grant") }
            assertTrue("Renewal: $renewal",renewal is HubResult.Ok && renewal.value.subtitles.size==1 && renewal.value.subtitles[0].track.language=="heb")
            runBlocking { OfflineSubtitleSync(repository,api).sync(
                PendingSubtitleSync(row.id,"he",0,0,"")
            ) }
            assertEquals("film",repository.mediaFile(row).readText())
            assertEquals(0,mediaRequests.get())
            assertEquals(1,subtitleRequests.get())
            val updated=checkNotNull(repository.completedForItem(item))
            assertEquals(String(bytes),repository.subtitleFile(updated,5,"srt").readText())
            assertTrue(repository.playbackPlan(item,"resume")!!.subtitleTracks.any { it.index==5 && it.external })
            truncateSubtitle.set(true)
            try {
                runBlocking { OfflineSubtitleSync(repository,api).sync(PendingSubtitleSync(row.id,"he",0,0,"")) }
                fail("A truncated subtitle must not replace the usable file")
            } catch (_: java.io.IOException) { }
            assertEquals(String(bytes),repository.subtitleFile(updated,5,"srt").readText())
            assertFalse(repository.localSubtitleFiles(updated).any { it.name.endsWith(".sync-part") })
            truncateSubtitle.set(false)
            runBlocking { OfflineSubtitleSync(repository,api).sync(PendingSubtitleSync(row.id,"he",0,0,"")) }
            assertEquals("film",repository.mediaFile(row).readText())
            assertEquals(0,mediaRequests.get())
            assertEquals(3,subtitleRequests.get())
        } finally {
            if(rowId.isNotEmpty()) repository.remove(rowId)
            HubSettings.save(context,oldUrl,oldToken)
            server.close();thread.join(2_000)
        }
    }
}
