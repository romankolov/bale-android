package ai.bale.client.feature.messaging

import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.UploadPayload
import ai.bale.client.domain.inferAttachmentKind
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

internal const val MAX_ATTACHMENT_BYTES = 64 * 1024 * 1024

internal suspend fun readPickedUri(context: Context, uri: Uri, forcePhotoOrVideo: Boolean = false): UploadPayload =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawMime = resolver.getType(uri).orEmpty()
        var displayName = ""
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && !cursor.isNull(idx)) displayName = cursor.getString(idx).orEmpty()
            }
        }
        if (displayName.isBlank()) displayName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val extFromName = displayName.substringAfterLast('.', "").lowercase()
        val mime = rawMime
            .ifBlank { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extFromName).orEmpty() }
            .ifBlank { if (forcePhotoOrVideo) "image/jpeg" else "application/octet-stream" }
        if (displayName.isBlank()) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty()
            displayName = if (ext.isNotBlank()) "attachment.$ext" else "attachment"
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                require(total <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds 64 MB limit" }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalStateException("Cannot read selected file")
        require(bytes.isNotEmpty()) { "Selected file is empty" }
        val inferred = inferAttachmentKind(mime, displayName)
        val kind = if (forcePhotoOrVideo && inferred != AttachmentKind.Photo && inferred != AttachmentKind.Video) {
            if (mime.startsWith("video/", ignoreCase = true)) AttachmentKind.Video else AttachmentKind.Photo
        } else {
            inferred
        }
        var width = 0
        var height = 0
        var durationSeconds = 0
        if (kind == AttachmentKind.Photo) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            width = bounds.outWidth.coerceAtLeast(0)
            height = bounds.outHeight.coerceAtLeast(0)
        } else if (kind == AttachmentKind.Video) {
            // Video attachments ship DocumentExVideo{w,h,duration}; zero-filled extensions are
            // rejected server-side, so probe the container before sending.
            runCatching {
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, uri)
                    width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    durationSeconds = ((retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                }
            }
        }
        UploadPayload(name = displayName, mimeType = mime, bytes = bytes, kind = kind, width = width, height = height, durationSeconds = durationSeconds)
    }

internal fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int = 720): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

internal fun decodeSampledFileBitmap(path: String, maxDimension: Int = 960): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

internal fun openCachedMediaExternally(context: Context, path: String, mimeType: String) {
    val file = File(path)
    if (!file.isFile) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(view, file.name)) }
}

/**
 * Records voice notes the way the Bale web client does: Opus in an Ogg container
 * (`audio/ogg; codecs=opus`), so notes stay tiny and play everywhere. Devices whose
 * MediaRecorder lacks OGG/OPUS fall back to Vorbis-in-Ogg, then to plain AAC-in-MP4.
 */
/**
 * Voice notes ship in the web client's container: Ogg/Opus, 48 kHz mono at 32 kbps. `MediaRecorder`
 * cannot mux Ogg at all, so the capture drives a `MediaCodec` Opus encoder through [OggOpusWriter]
 * and only falls back to plain AAC/M4A when the device exposes no Opus encoder.
 */
internal class VoiceNoteRecorder(private val context: Context) {
    @Volatile private var opus: OpusOggCapture? = null
    private var fallback: MediaRecorder? = null
    private var fallbackFile: File? = null
    private var startedAt = 0L

    fun start(): Boolean {
        stopAndDiscard()
        val oggTarget = File(voiceDir(), "voice_${System.currentTimeMillis()}.ogg")
        val capture = runCatching { OpusOggCapture(oggTarget).also { it.start() } }.getOrNull()
        if (capture != null) {
            opus = capture
            startedAt = System.currentTimeMillis()
            return true
        }
        oggTarget.delete()
        val m4aTarget = File(voiceDir(), "voice_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        val started = runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setOutputFile(m4aTarget.absolutePath)
            recorder.prepare()
            recorder.start()
            true
        }.getOrDefault(false)
        if (!started) {
            runCatching { recorder.release() }
            m4aTarget.delete()
            return false
        }
        fallback = recorder
        fallbackFile = m4aTarget
        startedAt = System.currentTimeMillis()
        return true
    }

    fun finish(): UploadPayload? {
        val seconds = ((System.currentTimeMillis() - startedAt) / 1_000L).toInt().coerceAtLeast(1)
        opus?.let { capture ->
            opus = null
            val file = capture.stopAndClose() ?: return null
            if (!file.isFile || file.length() <= 0L) {
                file.delete()
                return null
            }
            val payload = UploadPayload(
                name = file.name,
                mimeType = "audio/ogg",
                bytes = file.readBytes(),
                kind = AttachmentKind.Voice,
                durationSeconds = seconds,
            )
            file.delete()
            return payload
        }
        val recorder = fallback ?: return null
        val file = fallbackFile ?: return null
        fallback = null
        fallbackFile = null
        val stopped = runCatching { recorder.stop(); true }.getOrDefault(false)
        runCatching { recorder.release() }
        if (!stopped || !file.isFile || file.length() <= 0L) {
            file.delete()
            return null
        }
        val payload = UploadPayload(
            name = file.name,
            mimeType = "audio/mp4",
            bytes = file.readBytes(),
            kind = AttachmentKind.Voice,
            durationSeconds = seconds,
        )
        file.delete()
        return payload
    }

    fun stopAndDiscard() {
        opus?.let { capture ->
            opus = null
            capture.stopAndClose()?.delete()
        }
        fallback?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        fallback = null
        fallbackFile?.delete()
        fallbackFile = null
    }

    private fun voiceDir(): File = File(context.cacheDir, "media").apply { mkdirs() }
}

/** Blocking AudioRecord→MediaCodec→Ogg pipeline; every failure before [start] returns to the caller so the AAC fallback can run. */
private class OpusOggCapture(private val target: File) {
    @Volatile private var running = false
    @Volatile private var failed = false
    @Volatile private var workerFinished = false
    private var worker: Thread? = null
    private var encoder: MediaCodec? = null
    private var record: AudioRecord? = null

    fun start() {
        var mediaCodec: MediaCodec? = null
        var audio: AudioRecord? = null
        try {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
            }
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "no PCM input path" }
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord unavailable" }
            audio = record
            mediaCodec.start()
            record.startRecording()
            encoder = mediaCodec
            this.record = record
            running = true
            worker = thread(name = "opus-capture") { pump(mediaCodec, record) }
        } catch (failure: Throwable) {
            // Never leak a half-configured pipeline; the AAC fallback takes over from here.
            running = false
            runCatching { mediaCodec?.stop() }
            runCatching { mediaCodec?.release() }
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            encoder = null
            this.record = null
            throw failure
        }
    }

    private fun pump(codec: MediaCodec, audio: AudioRecord) {
        val stream = try {
            BufferedOutputStream(target.outputStream())
        } catch (error: Throwable) {
            failed = true
            running = false
            workerFinished = true
            return
        }
        val serial = (System.nanoTime() and 0x7FFFFFFFL).toInt()
        var pageSequence = 0
        var granule = 0L
        var queuedSamples = 0L
        val packets = mutableListOf<ByteArray>()
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(FRAME_SAMPLES * 2)
        fun drain(timeoutUs: Long): Boolean {
            var sawEos = false
            while (true) {
                val index = codec.dequeueOutputBuffer(info, timeoutUs)
                if (index < 0) break
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    packets += ByteArray(info.size).also { buffer.get(it) }
                    granule += FRAME_SAMPLES
                    if (packets.size >= PACKETS_PER_PAGE) {
                        stream.write(OggOpusWriter.page(serial, pageSequence++, granule, packets.toList()))
                        packets.clear()
                    }
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                if (eos) {
                    sawEos = true
                    break
                }
            }
            return sawEos
        }
        try {
            stream.write(OggOpusWriter.headerPages(serial = serial, firstSequence = pageSequence).also { pageSequence += 2 })
            var sawEos = false
            while (running && !sawEos) {
                val read = audio.read(pcm, 0, pcm.size)
                if (read < 0) {
                    failed = true
                    break
                }
                if (read == 0) continue
                if (read < pcm.size) {
                    pcm.fill(0, read, pcm.size)
                }
                var inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                var guard = 0
                while (inputIndex < 0 && running && guard++ < 50) inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.getInputBuffer(inputIndex)!!.apply { clear(); put(pcm) }
                    codec.queueInputBuffer(inputIndex, 0, pcm.size, queuedSamples * 1_000_000L / SAMPLE_RATE, 0)
                    queuedSamples += FRAME_SAMPLES
                }
                sawEos = drain(0L)
            }
            if (!sawEos) {
                // Graceful stop: inject an empty EOS buffer and let the encoder flush its tail.
                val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.getInputBuffer(inputIndex)!!.apply { clear() }
                    codec.queueInputBuffer(inputIndex, 0, 0, queuedSamples * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                var tailGuard = 0
                while (!sawEos && tailGuard++ < 100) sawEos = drain(50_000L)
                if (!sawEos) failed = true
            }
            stream.write(OggOpusWriter.page(serial, pageSequence, granule, packets.toList(), endOfStream = true))
            packets.clear()
        } catch (error: Throwable) {
            failed = true
        } finally {
            runCatching { stream.flush() }
            runCatching { stream.close() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { audio.stop() }
            runCatching { audio.release() }
            workerFinished = true
        }
    }

    fun stopAndClose(): File? {
        running = false
        worker?.join(5_000)
        worker = null
        // A worker that never reached its finally block left a truncated stream; drop it and
        // force-release the hardware so a hung capture cannot keep holding the microphone.
        if (!workerFinished) {
            failed = true
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
        return if (failed) {
            target.delete()
            null
        } else {
            target
        }
    }

    private companion object {
        const val MIME_TYPE = "audio/opus"
        const val SAMPLE_RATE = 48_000
        const val BIT_RATE = 32_000
        const val FRAME_SAMPLES = 960
        const val PACKETS_PER_PAGE = 40
        const val INPUT_TIMEOUT_US = 20_000L
    }
}

/**
 * Minimal Ogg container writer (RFC 3533 framing + RFC 7845 Opus headers) — pure byte maths so it is
 * unit-testable on the JVM. The CRC uses Ogg's non-reflected 0x04c11db7 polynomial with the field
 * zeroed during computation, which makes the CRC of a complete page decode to zero.
 */
internal object OggOpusWriter {
    private const val HEADER_TYPE_BOS = 0x02
    private const val HEADER_TYPE_EOS = 0x04

    private val crcTable: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        IntArray(256) { index ->
            var remainder = index shl 24
            repeat(8) {
                // The sign bit doubles as the x8000_0000 mask; 0x8000_0000 itself overflows Int.
                remainder = if (remainder < 0) (remainder shl 1) xor 0x04c1_1db7 else remainder shl 1
            }
            remainder
        }
    }

    fun crc(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var accumulator = 0
        for (index in from until to) {
            accumulator = (accumulator shl 8) xor crcTable[((accumulator ushr 24) xor (data[index].toInt() and 0xFF)) and 0xFF]
        }
        return accumulator
    }

    fun opusHead(channels: Int = 1, preSkip: Int = 312): ByteArray {
        val packet = ByteArray(19)
        "OpusHead".toByteArray().copyInto(packet)
        packet[8] = 1 // version
        packet[9] = channels.toByte()
        packet[10] = (preSkip and 0xFF).toByte()
        packet[11] = ((preSkip ushr 8) and 0xFF).toByte()
        intLE(48_000).copyInto(packet, 12)
        packet[16] = 0; packet[17] = 0 // output gain
        packet[18] = 0 // channel mapping family
        return packet
    }

    fun opusTags(vendor: String = "BaleAndroid"): ByteArray {
        val vendorBytes = vendor.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write("OpusTags".toByteArray())
        out.write(intLE(vendorBytes.size))
        out.write(vendorBytes)
        out.write(intLE(0)) // no user comments
        return out.toByteArray()
    }

    /** The two identification pages that must precede every OggOpus stream. */
    fun headerPages(serial: Int, firstSequence: Int = 0, channels: Int = 1): ByteArray {
        val first = page(serial, firstSequence, 0L, listOf(opusHead(channels)), beginStream = true)
        val second = page(serial, firstSequence + 1, 0L, listOf(opusTags()))
        return first + second
    }

    fun page(
        serial: Int,
        sequence: Int,
        granulePosition: Long,
        packets: List<ByteArray>,
        beginStream: Boolean = false,
        endOfStream: Boolean = false,
    ): ByteArray {
        val segmentCount = packets.sumOf { it.size / 255 + 1 }
        val bodySize = packets.sumOf { it.size }
        val page = ByteArray(27 + segmentCount + bodySize)
        "OggS".toByteArray().copyInto(page)
        page[4] = 0 // stream structure version
        var headerType = 0
        if (beginStream) headerType = headerType or HEADER_TYPE_BOS
        if (endOfStream) headerType = headerType or HEADER_TYPE_EOS
        page[5] = headerType.toByte()
        longLE(granulePosition).copyInto(page, 6)
        intLE(serial).copyInto(page, 14)
        intLE(sequence).copyInto(page, 18)
        intLE(0).copyInto(page, 22) // crc placeholder, patched below
        page[26] = segmentCount.toByte()
        var cursor = 27
        for (packet in packets) {
            var remaining = packet.size
            while (remaining >= 255) {
                page[cursor++] = -1 // 0xFF: full segment, packet continues
                remaining -= 255
            }
            page[cursor++] = remaining.toByte()
        }
        for (packet in packets) {
            packet.copyInto(page, cursor)
            cursor += packet.size
        }
        intLE(crc(page)).copyInto(page, 22)
        return page
    }

    private fun intLE(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

    private fun longLE(value: Long): ByteArray {
        val out = ByteArray(8)
        for (shift in 0 until 8) out[shift] = ((value ushr (8 * shift)) and 0xFF).toByte()
        return out
    }
}

internal class AudioClipPlayer {
    private var player: MediaPlayer? = null
    private val mutablePlayingPath = MutableStateFlow<String?>(null)
    val playingPath = mutablePlayingPath.asStateFlow()

    fun toggle(path: String) {
        if (mutablePlayingPath.value == path) {
            stop()
            return
        }
        stop()
        val mediaPlayer = MediaPlayer()
        runCatching {
            mediaPlayer.setDataSource(path)
            mediaPlayer.setOnCompletionListener { stop() }
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
            mutablePlayingPath.value = path
        }.onFailure {
            runCatching { mediaPlayer.release() }
            stop()
        }
    }

    fun stop() {
        player?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        player = null
        mutablePlayingPath.value = null
    }
}
