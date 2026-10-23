package ai.bale.client.network

import ai.bale.client.domain.Attachment
import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.ExPeerKind
import ai.bale.client.domain.Peer
import ai.bale.client.domain.ProtocolException
import ai.bale.client.domain.UploadPayload
import ai.bale.client.network.MessagingApi.Companion.exWire
import ai.bale.client.protocol.*
import com.google.protobuf.ByteString
import com.google.protobuf.Int32Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Nasim media upload/download transport over ai.bale.server.Files + HTTPS single-stream PUT/GET. */
class FilesApi(
    private val rpc: BaleRpc,
    private val http: OkHttpClient,
    private val allowLoopbackHttpForTests: Boolean = false,
    private val session: Long = rpc.sessionId,
) {
    suspend fun upload(
        accountId: Int,
        payload: UploadPayload,
        peer: Peer?,
        token: String?,
        exPeerType: Int = peer?.kind ?: ExPeerKind.PRIVATE,
    ): UploadedFile {
        require(payload.bytes.isNotEmpty() && payload.bytes.size <= MAX_MEDIA_BYTES) { "Invalid media size" }
        val mime = payload.mimeType.ifBlank { "application/octet-stream" }
        val safeName = sanitizeFileName(payload.name, mime)
        val sendType = when (payload.kind) {
            AttachmentKind.Photo -> SEND_PHOTO
            AttachmentKind.Video -> SEND_VIDEO
            AttachmentKind.Voice -> SEND_VOICE
            AttachmentKind.Audio -> SEND_AUDIO
            AttachmentKind.Sticker -> SEND_STICKER
            AttachmentKind.Document -> SEND_DOCUMENT
        }
        val resolvedExType = exPeerType.takeIf { it > 0 } ?: (peer?.kind ?: ExPeerKind.PRIVATE)
        val uploaderUid = if (resolvedExType == ExPeerKind.CHANNEL && peer != null) peer.id.toLong() else accountId.toLong()
        val req = Files_GetNasimFileUploadUrl_Request.newBuilder()
            .setExpectedSize(payload.bytes.size)
            .setUid(uploaderUid)
            .setName(safeName)
            .setMimeType(mime)
            .setSendType(SendType.newBuilder().setType(sendType))
            .apply { if (peer != null) setExPeer(peer.exWire(resolvedExType)) }
            .build()
        val resp = Files_GetNasimFileUploadUrl_Response.parseFrom(
            rpc.rpc(FILES_SERVICE, "GetNasimFileUploadUrl", req, session)
        )
        if (resp.fileId == 0L || resp.url.isBlank()) throw ProtocolException("Missing upload URL")
        val target = validateUrl(resp.url)
        putBytes(target, payload.bytes, token)
        return UploadedFile(resp.fileId, safeName, mime, payload.bytes.size, uploaderUid)
    }

    suspend fun download(attachment: Attachment, token: String?): ByteArray {
        require(attachment.fileId != 0L) { "Missing file ID" }
        val location = FileLocation.newBuilder()
            .setFileId(attachment.fileId)
            .setAccessHash(attachment.accessHash)
            .apply { if (attachment.storageVersion > 0) setFileStorageVersion(Int32Value.of(attachment.storageVersion)) }
            .build()
        val resp = Files_GetNasimFileUrl_Response.parseFrom(
            rpc.rpc(
                FILES_SERVICE,
                "GetNasimFileUrl",
                Files_GetNasimFileUrl_Request.newBuilder().setFile(location).setCiphertext(false).build(),
                session,
            )
        )
        val rawUrl = resp.fileUrl.url.ifBlank { resp.fileUrl.unsignedUrl.value }
        if (rawUrl.isBlank()) throw ProtocolException("No download URL returned")
        val target = validateUrl(rawUrl)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(target)
                .header("Origin", BaleEndpoint.ORIGIN)
                .header("User-Agent", BaleEndpoint.USER_AGENT)
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Media download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty media body")
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                body.byteStream().use { stream ->
                    var total = 0
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_MEDIA_BYTES) throw ProtocolException("Media exceeds maximum allowed size")
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            }
        }
    }

    private suspend fun putBytes(url: HttpUrl, data: ByteArray, token: String?) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Origin", BaleEndpoint.ORIGIN)
                .header("User-Agent", BaleEndpoint.USER_AGENT)
                .apply {
                    if (!token.isNullOrBlank()) header("Cookie", "access_token=$token")
                }
                .put(data.toRequestBody(UPLOAD_CONTENT_TYPE))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Upload HTTP ${response.code}")
            }
        }

    fun validateUrl(raw: String): HttpUrl {
        val parsed = raw.toHttpUrlOrNull() ?: throw ProtocolException("Invalid media URL")
        val loopback = allowLoopbackHttpForTests && (parsed.host == "127.0.0.1" || parsed.host == "localhost")
        if (!parsed.isHttps && !loopback) throw ProtocolException("Cleartext media URL rejected")
        return parsed
    }

    companion object {
        const val FILES_SERVICE = "ai.bale.server.Files"
        const val MAX_MEDIA_BYTES = 50 * 1024 * 1024
        const val SEND_PHOTO = 1
        const val SEND_VIDEO = 2
        const val SEND_VOICE = 3
        const val SEND_AUDIO = 5
        const val SEND_DOCUMENT = 6
        const val SEND_STICKER = 7
        private val UPLOAD_CONTENT_TYPE = "multipart/form-data".toMediaType()

        fun defaultExtensionForMime(mimeType: String): String = when (mimeType.lowercase().substringBefore(';').trim()) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            "video/webm" -> ".webm"
            "audio/ogg" -> ".ogg"
            "audio/mp4", "audio/mp4a-latm", "audio/x-m4a", "audio/aac" -> ".m4a"
            "audio/mpeg", "audio/mp3" -> ".mp3"
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            else -> ""
        }

        fun sanitizeFileName(raw: String, mimeType: String = ""): String {
            val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .trim().take(90)
            val base = cleaned.ifBlank { "attachment" }
            if ('.' in base.substringAfterLast(' ')) return base
            val ext = defaultExtensionForMime(mimeType).ifBlank { if (cleaned.isBlank()) ".bin" else "" }
            return base + ext
        }

        fun documentMessage(
            uploaded: UploadedFile,
            payload: UploadPayload,
            caption: String,
            accessHash: Long = uploaded.ownerAccessHash,
            thumbnail: ByteArray = byteArrayOf(),
            thumbWidth: Int = 0,
            thumbHeight: Int = 0,
        ): Message2 {
            val ext = Extra2.newBuilder().apply {
                when (payload.kind) {
                    AttachmentKind.Photo -> setDocumentExPhoto(DocumentExPhoto.newBuilder().setW(payload.width).setH(payload.height))
                    AttachmentKind.Video -> setDocumentExVideo(DocumentExVideo.newBuilder().setW(payload.width).setH(payload.height).setDuration(payload.durationSeconds))
                    AttachmentKind.Voice -> setDocumentExVoice(DocumentExVoice.newBuilder().setDuration(payload.durationSeconds))
                    AttachmentKind.Audio -> setDocumentExAudio(DocumentExAudio.newBuilder().setDuration(payload.durationSeconds).setTrack(uploaded.name))
                    else -> Unit
                }
            }.build()
            return Message2.newBuilder().setDocumentMessage(
                DocumentMessage.newBuilder()
                    .setFileId(uploaded.fileId)
                    .setAccessHash(accessHash)
                    .setFileSize(uploaded.sizeBytes)
                    .setName(uploaded.name)
                    .setMimeType(uploaded.mimeType)
                    .setExt(ext)
                    .apply {
                        if (thumbnail.isNotEmpty()) {
                            setThumb(Thumb.newBuilder().setW(thumbWidth).setH(thumbHeight).setThumb(ByteString.copyFrom(thumbnail)))
                        }
                        if (caption.isNotBlank()) setCaption(TextMessage.newBuilder().setText(caption.trim()))
                    }
            ).build()
        }
    }
}

data class UploadedFile(
    val fileId: Long,
    val name: String,
    val mimeType: String,
    val sizeBytes: Int,
    val ownerAccessHash: Long = 0L,
)
