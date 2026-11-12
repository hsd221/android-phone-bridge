package io.github.hsd221.phonebridge

import android.content.ClipData
import org.json.JSONObject

/** Never coerce a URI or Intent clip into text: that could read another provider. */
fun clipboardContent(clip: ClipData, path: String): JSONObject {
    val value = if (clip.itemCount > 0) clip.getItemAt(0).text else null
    return JSONObject().put("available", true).put("hasText", value != null)
        .put("text", value?.take(10000)?.toString() ?: JSONObject.NULL)
        .put("label", clip.description.label?.take(200)?.toString() ?: JSONObject.NULL)
        .put("truncated", value != null && value.length > 10000)
        .put("path", path).put("capturedAt", System.currentTimeMillis())
}
