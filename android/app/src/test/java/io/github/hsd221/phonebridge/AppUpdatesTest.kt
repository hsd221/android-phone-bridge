package io.github.hsd221.phonebridge

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AppUpdatesTest {
    private fun version(value: String) = requireNotNull(AppVersion.parse(value))
    private fun download(tag: String, name: String = "phone-bridge-${tag.removePrefix("v")}.apk") =
        "https://github.com/hsd221/android-phone-bridge/releases/download/$tag/$name"
    private fun release(tag: String, draft: Boolean = false, assets: JSONArray? = null): JSONObject = JSONObject().apply {
        put("tag_name", tag)
        put("draft", draft)
        put("prerelease", '-' in tag)
        put("body", "本次更新：$tag")
        put("html_url", "https://github.com/hsd221/android-phone-bridge/releases/tag/$tag")
        put("assets", assets ?: JSONArray().put(asset(tag)))
    }
    private fun asset(tag: String, name: String = "phone-bridge-${tag.removePrefix("v")}.apk", url: String = download(tag, name), state: String = "uploaded") =
        JSONObject().apply {
            put("name", name)
            put("browser_download_url", url)
            put("state", state)
            put("content_type", "application/vnd.android.package-archive")
            put("size", 2048)
        }
    private fun choose(vararg releases: JSONObject) = AppUpdates.latestFromJson(JSONArray(releases.toList()).toString())

    @Test fun versionsFollowSemverRatherThanLexicalOrPublicationOrder() {
        val ascending = listOf("0.3.0-pre.2", "0.3.0-pre.9", "v0.3.0-pre.10", "0.3.0-rc.1", "0.3.0", "0.3.1-pre.1", "0.4.0-pre.1", "1.0.0")
        ascending.zipWithNext().forEach { (older, newer) ->
            assertTrue("$newer must be newer than $older", version(newer) > version(older))
        }
        assertTrue(version("0.3.0-pre.1") < version("0.3.0-pre.alpha"))
        assertTrue(version("0.3.0-pre") < version("0.3.0-pre.1"))
        assertEquals(0, version("v0.3.0+build.1").compareTo(version("0.3.0+build.9")))
        assertEquals(0, version("0.3.0-pre.7+first").compareTo(version("0.3.0-pre.7+second")))
    }

    @Test fun invalidVersionTagsCannotBecomeUpdates() {
        listOf("", "latest", "v0.3", "0.3", "01.3.0", "0.03.0", "0.3.00", "0.3.0-pre.01", "0.3.0-", "0.3.0+", "0.3.0-pre..1", "0.3.0 pre.1").forEach {
            assertNull("Should reject invalid SemVer: $it", AppVersion.parse(it))
        }
    }

    @Test fun prereleasesAreIncludedAndLargestVersionWinsAcrossAnUnsortedFeed() {
        val chosen = choose(release("v0.3.0-pre.9"), release("v0.2.0"), release("v0.3.0-pre.10"), release("v0.3.0-pre.2"))
        assertEquals("v0.3.0-pre.10", chosen.tag)
        assertEquals(download(chosen.tag), chosen.downloadUrl)
        assertEquals("本次更新：v0.3.0-pre.10", chosen.notes)
        assertEquals("v0.3.0", choose(release("v0.3.0-pre.10"), release("v0.3.0")).tag)
        assertEquals("v0.4.0-pre.1", choose(release("v0.3.0"), release("v0.4.0-pre.1")).tag)
    }

    @Test fun draftsInvalidTagsAndReleasesWithoutTheUploadedAppAreSkipped() {
        val fallback = release("v0.3.0-pre.7")
        val demoOnly = JSONArray().put(asset("v8.0.0", "demo-debug.apk")).put(asset("v8.0.0", "app-debug-androidTest.apk"))
        val uploading = JSONArray().put(asset("v7.0.0", state = "new"))
        val selected = choose(release("v9.0.0", draft = true), release("v8.0.0", assets = demoOnly),
            release("v7.0.0", assets = uploading), release("v6.0.0", assets = JSONArray()), release("latest"), fallback)
        assertEquals("v0.3.0-pre.7", selected.tag)
    }

    @Test fun apkLinksMustBelongToThisRepositoryAndTheSelectedTag() {
        val tag = "v0.4.0-pre.1"
        val valid = download(tag)
        val foreignLinks = listOf(
            valid.replace("https://", "http://"),
            valid.replace("github.com", "github.com.evil.example"),
            valid.replace("github.com", "evil.example"),
            valid.replace("/hsd221/", "/somebody/"),
            valid.replace("/android-phone-bridge/", "/other-repository/"),
            valid.replace("/download/$tag/", "/download/v0.2.0/"),
            valid.replace("github.com/", "github.com@evil.example/"),
        )
        for (link in foreignLinks) {
            val rejected = release(tag, assets = JSONArray().put(asset(tag, url = link)))
            assertEquals("Unsafe link was accepted: $link", "v0.3.0-pre.7", choose(rejected, release("v0.3.0-pre.7")).tag)
        }
    }

    @Test fun emptyOrMalformedResponsesDoNotClaimThatTheAppIsCurrent() {
        listOf("[]", "{}", "not json", "[{\"tag_name\":\"v1.0.0\"}]").forEach { json ->
            assertThrows("Unavailable release metadata must report a failure", Exception::class.java) { AppUpdates.latestFromJson(json) }
        }
    }

    @Test fun publicReleaseRequestUsesGithubApiWithoutBridgeCredentials() = runBlocking {
        val captured = AtomicReference<Request>()
        val body = JSONArray().put(release("v0.3.0-pre.10")).toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured.set(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        assertEquals("v0.3.0-pre.10", AppUpdates.latest(client).tag)
        val request = captured.get()
        assertEquals("https", request.url.scheme)
        assertEquals("api.github.com", request.url.host)
        assertEquals("/repos/hsd221/android-phone-bridge/releases", request.url.encodedPath)
        assertEquals("GET", request.method)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertFalse("/latest excludes prereleases", request.url.encodedPath.endsWith("/latest"))
    }

    @Test fun githubHttpFailureIsReportedWithoutExposingTheResponseBody() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("untrusted server detail must not reach the user".toResponseBody()).build()
        }.build()
        var failure: Exception? = null
        try { AppUpdates.latest(client) } catch (caught: Exception) { failure = caught }
        assertNotNull("HTTP failure was treated as a successful update check", failure)
        assertFalse(failure!!.message.orEmpty().contains("untrusted server detail"))
    }
}
