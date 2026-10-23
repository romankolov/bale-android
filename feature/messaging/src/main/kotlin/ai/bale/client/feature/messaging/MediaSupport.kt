package ai.bale.client.feature.messaging

import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.UploadPayload
import ai.bale.client.domain.inferAttachmentKind
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

internal const val MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024

internal suspend fun readPickedUri(context: Context, uri: Uri, forcePhotoOrVideo: Boolean = false): UploadPayload? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawMime = resolver.getType(uri).orEmpty()
        var displayName = "attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && !cursor.isNull(idx)) displayName = cursor.getString(idx)
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8_192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                require(total <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds 50 MB limit" }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        val mime = rawMime.ifBlank { if (forcePhotoOrVideo) "image/jpeg" else "application/octet-stream" }
        val kind = inferAttachmentKind(mime)
        var width = 0
        var height = 0
        if (kind == AttachmentKind.Photo) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            width = bounds.outWidth.coerceAtLeast(0)
            height = bounds.outHeight.coerceAtLeast(0)
        }
        UploadPayload(name = displayName, mimeType = mime, bytes = bytes, kind = kind, width = width, height = height)
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

internal class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    fun start(): Boolean {
        stopAndDiscard()
        val target = File(File(context.cacheDir, "media").apply { mkdirs() }, "voice_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return runCatching {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(64_000)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setOutputFile(target.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            outputFile = target
            startedAt = System.currentTimeMillis()
            true
        }.getOrElse {
            runCatching { mediaRecorder.release() }
            target.delete()
            false
        }
    }

    fun finish(): UploadPayload? {
        val active = recorder ?: return null
        val file = outputFile ?: return null
        recorder = null
        outputFile = null
        val duration = ((System.currentTimeMillis() - startedAt) / 1_000L).toInt().coerceAtLeast(1)
        val ok = runCatching {
            active.stop()
            true
        }.getOrDefault(false)
        runCatching { active.release() }
        if (!ok || !file.isFile || file.length() <= 0L) {
            file.delete()
            return null
        }
        val bytes = file.readBytes()
        file.delete()
        return UploadPayload(
            name = "voice_${System.currentTimeMillis()}.m4a",
            mimeType = "audio/mp4a-latm",
            bytes = bytes,
            kind = AttachmentKind.Voice,
            durationSeconds = duration,
        )
    }

    fun stopAndDiscard() {
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
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
