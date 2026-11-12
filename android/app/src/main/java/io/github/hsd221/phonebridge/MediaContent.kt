package io.github.hsd221.phonebridge

import java.io.ByteArrayOutputStream
import java.io.InputStream

data class MediaReference(val kind: String, val id: Long)

private val mediaUriPattern = Regex("content://media/external/(images|video)/media/([1-9][0-9]{0,18})")
private val mediaMimePattern = Regex("(image|video)/[A-Za-z0-9][A-Za-z0-9!#$%&'+.^_`|~-]*", RegexOption.IGNORE_CASE)

/** Accept only the canonical MediaStore item URI shape emitted by phone_media. */
fun parseMediaUri(uri: String): MediaReference? {
    val match = mediaUriPattern.matchEntire(uri) ?: return null
    val id = match.groupValues[2].toLongOrNull() ?: return null
    return MediaReference(if (match.groupValues[1] == "images") "images" else "videos", id)
}

fun isMediaMimeType(kind: String, mime: String): Boolean {
    val match = mediaMimePattern.matchEntire(mime) ?: return false
    return when (kind) {
        "images" -> match.groupValues[1].equals("image", ignoreCase = true)
        "videos" -> match.groupValues[1].equals("video", ignoreCase = true)
        else -> false
    }
}

/** Read at most the limit plus one sentinel byte, regardless of provider metadata. */
fun readMediaBytes(stream: InputStream, maxBytes: Int, ensure: () -> Unit): ByteArray {
    if (maxBytes !in 1..4194304) throw ToolFailure("INVALID_ARGUMENT", text(R.string.err_argument_invalid, "maxBytes"))
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        ensure()
        val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
        if (count < 0) break
        if (count == 0) {
            val byte = stream.read()
            if (byte < 0) break
            output.write(byte)
        } else output.write(buffer, 0, count)
        if (output.size() > maxBytes) throw ToolFailure("MEDIA_TOO_LARGE", text(R.string.err_media_too_large, maxBytes))
    }
    ensure()
    return output.toByteArray()
}
