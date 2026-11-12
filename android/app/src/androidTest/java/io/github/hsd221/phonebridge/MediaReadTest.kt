package io.github.hsd221.phonebridge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class MediaReadTest {
    @get:Rule val appUpdates = AppUpdateFixture()
    private val session = DataMcpSession()
    private val created = mutableListOf<Uri>()
    private fun insert(kind: String, mime: String, name: String, bytes: ByteArray): Uri {
        val collection = if (kind == "images") MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (kind == "images") "Pictures/PhoneBridgeSynthetic" else "Movies/PhoneBridgeSynthetic")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = session.context.contentResolver.insert(collection, values) ?: error("Cannot create synthetic media")
        created.add(uri)
        session.context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        session.context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return ContentUris.withAppendedId(collection, ContentUris.parseId(uri))
    }
    private fun args(uri: Uri, maxBytes: Int? = null) = JSONObject().put("uri", uri.toString()).apply { maxBytes?.let { put("maxBytes", it) } }
    private fun verify(uri: Uri, expected: ByteArray, expectedType: String, maxBytes: Int? = null) {
        val result = session.call("phone_media_read", args(uri, maxBytes))
        assertFalse(result.toString(), result.optBoolean("isError"))
        val metadata = session.metadata(result)
        assertTrue(metadata.toString(), metadata.getBoolean("ok"))
        val data = metadata.getJSONObject("data")
        assertFalse("MCP metadata duplicated binary contents", data.has("base64"))
        assertEquals(uri.toString(), data.getString("uri"))
        assertEquals(expected.size.toLong(), data.getLong("sizeBytes"))
        assertTrue(data.getLong("capturedAt") > 0)
        val content = result.getJSONArray("content").getJSONObject(0)
        assertEquals(expectedType, content.getString("type"))
        val encoded = if (expectedType == "image") content.getString("data") else content.getJSONObject("resource").getString("blob")
        assertArrayEquals(expected, Base64.decode(encoded, Base64.DEFAULT))
    }
    @Test fun mediaContentRoundTripPermissionsLimitsAndDefaultDeny(): Unit = runBlocking {
        assumeTrue(session.arguments.getString("dataToolTest") == "true")
        val prefs = session.prefs
        assertNull(prefs.identity)
        assertTrue(prefs.preferences.edit().clear().putBoolean("cap_MEDIA", true).commit())
        assertFalse("Legacy media access silently enabled content reads", "phone_media_read" in prefs.policy().tools)
        assertTrue(prefs.preferences.edit().putBoolean("tool_permissions_configured", true).putBoolean("tool_phone_media", true).commit())
        assertFalse("Metadata permission silently enabled content reads", "phone_media_read" in prefs.policy().tools)
        prefs.setCapabilities(true)
        assertFalse("Legacy capability API enabled new binary access", "phone_media_read" in prefs.policy().tools)
        prefs.setFlag("cap_MEDIA", true)
        assertFalse("Legacy capability flag enabled new binary access", "phone_media_read" in prefs.policy().tools)
        prefs.setTools(true)
        assertTrue("An explicit enable-all-tools request must include content reads", "phone_media_read" in prefs.policy().tools)
        prefs.setFlag("cap_MEDIA", true)
        assertTrue("A legacy enable must preserve an existing explicit content grant", "phone_media_read" in prefs.policy().tools)
        prefs.setFlag("cap_MEDIA", false)
        assertFalse("Disabling media capability did not withdraw content access", "phone_media_read" in prefs.policy().tools)
        prefs.setPermissions(tools = setOf("phone_media"))
        session.start()
        try {
            val png = ByteArrayOutputStream().use { output ->
                Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff2277aa.toInt()) }
                    .also { assertTrue(it.compress(Bitmap.CompressFormat.PNG, 100, output)); it.recycle() }
                output.toByteArray()
            }
            val image = insert("images", "image/png", "synthetic-data.png", png)
            val videoBytes = byteArrayOf(0, 0, 0, 20) + "ftypisomsynthetic".toByteArray()
            val video = insert("videos", "video/mp4", "synthetic-data.mp4", videoBytes)
            session.denied("CAPABILITY_DENIED", "phone_media_read", args(image))
            prefs.setPermissions(tools = setOf("phone_media_read")); prefs.shizukuEnabled = false
            session.denied("MEDIA_PERMISSION_REQUIRED", "phone_media_read", args(image))
            session.shell("pm grant ${session.context.packageName} ${if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"}")
            verify(image, png, "image")
            verify(image, png, "image", png.size)
            session.denied("MEDIA_TOO_LARGE", "phone_media_read", args(image, png.size - 1))
            session.denied("MEDIA_NOT_FOUND", "phone_media_read", args(Uri.parse("content://media/external/images/media/${Long.MAX_VALUE}")))
            if (Build.VERSION.SDK_INT >= 33) {
                session.denied("MEDIA_PERMISSION_REQUIRED", "phone_media_read", args(video))
                session.shell("pm grant ${session.context.packageName} android.permission.READ_MEDIA_VIDEO")
            }
            verify(video, videoBytes, "resource")
            val heicBytes = "synthetic image container bytes".toByteArray()
            val heic = insert("images", "image/heic", "synthetic-data.heic", heicBytes)
            verify(heic, heicBytes, "resource")
            prefs.setFlag("pausedOperations", true)
            verify(image, png, "image")
            prefs.setFlag("pausedAll", true)
            session.denied("PAUSED", "phone_media_read", args(image))
            prefs.setFlag("pausedAll", false); prefs.setFlag("pausedOperations", false)
            prefs.setPermissions(tools = setOf("phone_media"))
            session.denied("CAPABILITY_DENIED", "phone_media_read", args(image))
            for (uri in listOf("file:///sdcard/test.png", "content://io.github.hsd221.phonebridge.test.data/never-read",
                "content://media/external/images/media/1?x=1", "content://media/external/images/media/9223372036854775808")) {
                try {
                    PhoneTools(BridgeService(), prefs).execute("phone_media_read", JSONObject().put("uri", uri)) {}
                    fail("Phone accepted a noncanonical or out-of-range media URI")
                } catch (failure: ToolFailure) { assertEquals("INVALID_ARGUMENT", failure.code) }
            }
            session.context.contentResolver.query(Uri.parse("content://io.github.hsd221.phonebridge.test.data/state"), null, null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("An arbitrary content provider was read", 0, it.getInt(0))
            }
            session.note("PASS: media image/video bytes through real MCP, image/resource encoding, exact size limit, independent grants, Android permissions, pauses, missing rows, canonical URI validation, and no arbitrary provider reads.")
        } finally {
            try { created.forEach { session.context.contentResolver.delete(it, null, null) } }
            finally { session.close() }
        }
    }
}
