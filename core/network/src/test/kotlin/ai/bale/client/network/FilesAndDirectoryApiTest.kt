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

    @Test fun `single PUT upload streams full raw bytes and never invents an access hash`() = runTest {
        val server = MockWebServer()
        // One PUT for the photo upload, one for the channel document upload below.
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
                    .setChunkSize(0)
                    .build().toByteArray()
            }
            val api = FilesApi(rpc, http, allowLoopbackHttpForTests = true)
            val rawBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
            val payload = UploadPayload("photo.jpg", "image/jpeg", rawBytes, AttachmentKind.Photo, width = 100, height = 80)
            val progress = mutableListOf<Float>()
            val uploaded = api.upload(7, payload, Peer(42, 1, 123), "fixture-jwt", onProgress = { progress += it })
            assertEquals(901L, uploaded.fileId)
            assertEquals(0L, uploaded.ownerAccessHash)
            assertTrue(progress.first() < progress.last())
            assertEquals(1f, progress.last())
            val firstUploadReq = rpc.calls.single().third as Files_GetNasimFileUploadUrl_Request
            assertEquals(7L, firstUploadReq.uid)
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

            // Channel uploads use the channel peer.id as the uploader uid.
            val pdfPayload = UploadPayload("report", "application/pdf", byteArrayOf(9, 9, 9), AttachmentKind.Document)
            val chanUploaded = api.upload(7, pdfPayload, Peer(777, 2, 999), "fixture-jwt", exPeerType = ExPeerKind.CHANNEL)
            val chanUploadReq = rpc.calls.last().third as Files_GetNasimFileUploadUrl_Request
            assertEquals(777L, chanUploadReq.uid)
            assertEquals(ExPeerKind.CHANNEL, chanUploadReq.exPeer.type)
            assertEquals("report.pdf", chanUploadReq.name)
            assertEquals(0L, chanUploaded.ownerAccessHash)

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
            assertEquals(0L, doc.documentMessage.accessHash)
            assertEquals(100, doc.documentMessage.ext.documentExPhoto.w)
            assertEquals("Holiday", doc.documentMessage.caption.text)
            assertEquals(50, doc.documentMessage.thumb.w)
            assertArrayEquals(thumb, doc.documentMessage.thumb.thumb.toByteArray())

            // Plain documents must not carry an empty `ext` message; the web wire omits it entirely.
            val pdfDoc = FilesApi.documentMessage(
                uploaded = chanUploaded,
                payload = pdfPayload,
                caption = "",
                accessHash = 0L,
            )
            assertFalse(pdfDoc.documentMessage.hasExt())
            assertFalse(pdfDoc.documentMessage.hasCaption())
            assertEquals("application/pdf", pdfDoc.documentMessage.mimeType)
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `chunkSize answers are ignored; the whole file still travels as one PUT`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        val http = OkHttpClient()
        try {
            val rpc = StubRpc()
            rpc.responder = { _, _, _ ->
                Files_GetNasimFileUploadUrl_Response.newBuilder()
                    .setFileId(4242L)
                    .setUrl(server.url("/upload").toString())
                    .setChunkSize(4)
                    .build().toByteArray()
            }
            val api = FilesApi(rpc, http, allowLoopbackHttpForTests = true)
            val rawBytes = ByteArray(10) { it.toByte() }
            val progress = mutableListOf<Float>()
            val uploaded = api.upload(
                7,
                UploadPayload("deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", rawBytes, AttachmentKind.Document),
                Peer(42, 1, 123),
                null,
                onProgress = { progress += it },
            )
            assertEquals(4242L, uploaded.fileId)
            assertEquals(1f, progress.last())

            // The server never assembles partial uploads; splitting on chunkSize lost every file
            // larger than one chunk. Exactly one PUT, full bytes, no chunk bookkeeping headers.
            assertEquals(1L, server.requestCount.toLong())
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertNull(put.getHeader("chunk"))
            assertNull(put.getHeader("no"))
            assertArrayEquals(rawBytes, put.body.readByteArray())
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `duplicate answers skip the PUT entirely`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val rpc = StubRpc()
            rpc.responder = { _, _, _ ->
                Files_GetNasimFileUploadUrl_Response.newBuilder()
                    .setFileId(55L)
                    .setDuplicate(true)
                    .build().toByteArray()
            }
            val progress = mutableListOf<Float>()
            val uploaded = FilesApi(rpc, OkHttpClient(), allowLoopbackHttpForTests = true).upload(
                7,
                UploadPayload("a.bin", "application/octet-stream", byteArrayOf(1, 2), AttachmentKind.Document),
                null,
                null,
                onProgress = { progress += it },
            )
            assertEquals(55L, uploaded.fileId)
            assertEquals(0, server.requestCount)
            assertEquals(listOf(1f), progress)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `download reports byte progress and ends at one`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(20_000) { (it % 251).toByte() }))
        )
        server.start()
        try {
            val rpc = StubRpc()
            rpc.responder = { _, _, _ ->
                Files_GetNasimFileUrl_Response.newBuilder()
                    .setFileUrl(
                        FileUrl.newBuilder().setUrl(server.url("/file").toString()).build()
                    )
                    .build().toByteArray()
            }
            val progress = mutableListOf<Float>()
            val bytes = FilesApi(rpc, OkHttpClient(), allowLoopbackHttpForTests = true)
                .download(Attachment(fileId = 9L, accessHash = 1L, name = "f.bin", mimeType = "application/octet-stream", sizeBytes = 20_000, kind = AttachmentKind.Document), null, onProgress = { progress += it })
            assertEquals(20_000, bytes.size)
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
            assertEquals(1f, progress.last())
        } finally {
            server.shutdown()
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
