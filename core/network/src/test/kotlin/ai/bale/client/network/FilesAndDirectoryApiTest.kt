package ai.bale.client.network

import ai.bale.client.domain.Attachment
import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.Connection
import ai.bale.client.domain.ExPeerKind
import ai.bale.client.domain.Peer
import ai.bale.client.domain.ProtocolException
import ai.bale.client.domain.UploadPayload
import ai.bale.client.protocol.*
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class FilesAndDirectoryApiTest {
    private class StubRpc : BaleRpc {
        override val state = MutableStateFlow(Connection.Connected)
        override var sessionId = 1L
        override val updates = MutableSharedFlow<SessionUpdate>()
        val calls = mutableListOf<Triple<String, String, MessageLite>>()
        var responder: (String, String, MessageLite) -> ByteArray = { _, _, _ -> byteArrayOf() }
        override suspend fun rpc(service: String, method: String, request: MessageLite, expectedSession: Long): ByteArray {
            calls += Triple(service, method, request)
            return responder(service, method, request)
        }
    }

    @Test fun `single PUT upload streams full raw bytes without chunk headers and populates document metadata`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                if (req.url.host.equals(BaleEndpoint.HOST, ignoreCase = true)) {
                    b.header("app_version", "176652")
                }
                chain.proceed(b.build())
            }
            .build()
        try {
            val rpc = StubRpc()
            rpc.responder = { _, method, _ ->
                assertEquals("GetNasimFileUploadUrl", method)
                Files_GetNasimFileUploadUrl_Response.newBuilder()
                    .setFileId(901L)
                    .setUrl(server.url("/upload").toString())
                    .setChunkSize(4)
                    .build().toByteArray()
            }
            val api = FilesApi(rpc, http, allowLoopbackHttpForTests = true)
            val rawBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
            val payload = UploadPayload("photo.jpg", "image/jpeg", rawBytes, AttachmentKind.Photo, width = 100, height = 80)
            val uploaded = api.upload(7, payload, Peer(42, 1, 123), "fixture-jwt")
            assertEquals(901L, uploaded.fileId)
            assertEquals(7L, uploaded.ownerAccessHash)
            val firstUploadReq = rpc.calls.single().third as Files_GetNasimFileUploadUrl_Request
            assertEquals(7, firstUploadReq.uploaderUid.value)
            assertEquals(1, firstUploadReq.exPeer.type)

            val putReq = server.takeRequest()
            assertEquals("PUT", putReq.method)
            assertEquals(6L, putReq.bodySize)
            assertArrayEquals(rawBytes, putReq.body.readByteArray())
            assertNull(putReq.getHeader("chunk"))
            assertNull(putReq.getHeader("no"))
            assertNull(putReq.getHeader("app_version"))
            assertEquals("https://web.bale.ai", putReq.getHeader("Origin"))
            assertEquals("multipart/form-data", putReq.getHeader("Content-Type"))
            assertEquals("access_token=fixture-jwt", putReq.getHeader("Cookie"))
            assertEquals(0, server.requestCount - 1)

            // Verify channel uploads use channel peer.id as uploaderUid & ownerAccessHash and append default extensions
            val pdfPayload = UploadPayload("report", "application/pdf", byteArrayOf(9, 9, 9), AttachmentKind.Document)
            val chanUploaded = api.upload(7, pdfPayload, Peer(777, 2, 999), "fixture-jwt", exPeerType = ExPeerKind.CHANNEL)
            val chanUploadReq = rpc.calls.last().third as Files_GetNasimFileUploadUrl_Request
            assertEquals(777, chanUploadReq.uploaderUid.value)
            assertEquals(ExPeerKind.CHANNEL, chanUploadReq.exPeer.type)
            assertEquals("report.pdf", chanUploadReq.fileInfo.name.value)
            assertEquals(777L, chanUploaded.ownerAccessHash)

            val thumb = byteArrayOf(11, 22, 33)
            val doc = FilesApi.documentMessage(
                uploaded = uploaded,
                payload = payload,
                caption = "Holiday",
                thumbnail = thumb,
                thumbWidth = 50,
                thumbHeight = 40,
            )
            assertEquals(901L, doc.documentMessage.fileId)
            assertEquals(7L, doc.documentMessage.accessHash)
            assertEquals(100, doc.documentMessage.ext.documentExPhoto.w)
            assertEquals("Holiday", doc.documentMessage.caption.text)
            assertEquals(50, doc.documentMessage.thumb.w)
            assertArrayEquals(thumb, doc.documentMessage.thumb.thumb.toByteArray())
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `download streams verified media and strictly blocks cleartext remote URLs`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(9, 8, 7, 6))))
        server.start()
        val http = OkHttpClient()
        try {
            val rpc = StubRpc()
            val attachment = Attachment(555L, 999L, 1, "clip.ogg", "audio/ogg", 4, AttachmentKind.Voice, durationSeconds = 3)
            rpc.responder = { _, _, _ ->
                Files_GetNasimFileUrl_Response.newBuilder()
                    .setFileUrl(FileUrl.newBuilder().setUrl("http://insecure.example.com/clip.ogg"))
                    .build().toByteArray()
            }
            val strictApi = FilesApi(rpc, http, allowLoopbackHttpForTests = false)
            try {
                strictApi.download(attachment, "jwt")
                fail("Cleartext URL must fail")
            } catch (_: ProtocolException) { }

            rpc.responder = { _, _, _ ->
                Files_GetNasimFileUrl_Response.newBuilder()
                    .setFileUrl(FileUrl.newBuilder().setUrl(server.url("/media/clip.ogg").toString()))
                    .build().toByteArray()
            }
            val bytes = FilesApi(rpc, http, allowLoopbackHttpForTests = true).download(attachment, "jwt")
            assertArrayEquals(byteArrayOf(9, 8, 7, 6), bytes)
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `directory API routes contacts groups avatar accessHash stories and auth session operations`() = runTest {
        val rpc = StubRpc()
        rpc.responder = { service, method, _ ->
            when ("$service/$method") {
                "bale.groups.v1.Groups/CreateGroup" -> Groups_CreateGroup_Response.newBuilder()
                    .setGroup(Groups2.newBuilder().setId(300).setAccessHash(888).setTitle("Engineering")).build().toByteArray()
                "bale.story.v1.Story/AddStory" -> Msg_6806_ad481ac22d__48949_u.newBuilder().setStoryId("st-1").build().toByteArray()
                "bale.auth.v1.Auth/GetAuthSessions" -> Auth_GetAuthSessions_Response.newBuilder()
                    .addUserAuths(UserAuths.newBuilder().setId(5).setAppTitle("Android").setDeviceTitle("Pixel")).build().toByteArray()
                else -> byteArrayOf()
            }
        }
        val api = DirectoryApi(rpc)
        val created = api.createCommunity(12L, "Engineering", isChannel = false, handle = "", members = listOf(Peer(10, 1, 20), Peer(11, 1, 21)))
        assertEquals(300, created.id)
        assertEquals(2, (rpc.calls.first().third as Groups_CreateGroup_Request).usersCount)
        api.editAvatar(901L, 7L)
        val avatarReq = rpc.calls.last().third as Users_EditAvatar_Request
        assertEquals(901L, avatarReq.fileLocation.fileId)
        assertEquals(7L, avatarReq.fileLocation.accessHash)
        assertEquals("st-1", api.addTextStory("Hello story"))
        assertEquals(5, api.authSessions().single().id)
        api.terminateSession(5)
        api.remoteSignOut()
        assertEquals("SignOut", rpc.calls.last().second)
    }
}
