package io.github.hsd221.phonebridge

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** SemVer ordering, including numeric prerelease identifiers; build metadata has no precedence. */
internal class AppVersion private constructor(
    private val numbers: List<BigInteger>,
    private val prerelease: List<String>,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            left.compareTo(right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when { prerelease == other.prerelease -> 0; prerelease.isEmpty() -> 1; else -> -1 }
        }
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")
        fun parse(raw: String): AppVersion? {
            val match = pattern.matchEntire(raw) ?: return null
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (prerelease.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return AppVersion((1..3).map { match.groupValues[it].toBigInteger() }, prerelease)
        }
    }
}

internal data class AppRelease(val tag: String, val version: AppVersion, val downloadUrl: String, val notes: String)
internal class UpdateCheckException(message: String) : Exception(message)

/** Public release metadata only. This client never uses the paired Bridge identity or credentials. */
internal object AppUpdates {
    const val RELEASES_URL = "https://github.com/hsd221/android-phone-bridge/releases"
    private const val API_URL = "https://api.github.com/repos/hsd221/android-phone-bridge/releases?per_page=100"
    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    // /releases/latest excludes prereleases. Select the highest downloadable version from the list.
    fun latestFromJson(json: String): AppRelease {
        val releases = try { JSONArray(json) } catch (_: Exception) {
            throw UpdateCheckException("更新信息格式异常，请稍后重试或查看发布页面。")
        }
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.optJSONObject(index) ?: return@mapNotNull null
            if (release.optBoolean("draft")) return@mapNotNull null
            val tag = release.optString("tag_name")
            val version = AppVersion.parse(tag) ?: return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val download = (0 until assets.length()).firstNotNullOfOrNull { assetIndex ->
                val asset = assets.optJSONObject(assetIndex) ?: return@firstNotNullOfOrNull null
                val name = asset.optString("name")
                if (!name.startsWith("phone-bridge-") || !name.endsWith(".apk") ||
                    asset.optString("state") != "uploaded" || asset.optLong("size") <= 0) return@firstNotNullOfOrNull null
                val url = asset.optString("browser_download_url").toHttpUrlOrNull() ?: return@firstNotNullOfOrNull null
                val expected = RELEASES_URL.toHttpUrl().newBuilder().addPathSegment("download")
                    .addPathSegment(tag).addPathSegment(name).build()
                url.toString().takeIf { url == expected }
            } ?: return@mapNotNull null
            val notes = if (release.isNull("body")) "" else release.optString("body").take(4000).trim()
            AppRelease(tag, version, download, notes)
        }.maxByOrNull { it.version }
            ?: throw UpdateCheckException("暂未找到可下载的版本，请稍后重试或查看发布页面。")
    }

    suspend fun latest(client: OkHttpClient = defaultClient): AppRelease = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "PhoneBridge/${BuildConfig.VERSION_NAME}").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val message = if (e is SocketTimeoutException) "连接超时，请检查网络后重试。" else "无法连接 GitHub，请检查网络后重试。"
                continuation.resumeWithException(UpdateCheckException(message))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (it.code == 403 || it.code == 429) throw UpdateCheckException("暂时无法检查更新，请稍后重试或查看发布页面。")
                        if (!it.isSuccessful) throw UpdateCheckException("检查更新失败（HTTP ${it.code}），请稍后重试。")
                        latestFromJson(it.body?.string() ?: "")
                    }
                }
                result.fold(continuation::resume) { failure ->
                    continuation.resumeWithException(if (failure is UpdateCheckException) failure else UpdateCheckException("读取更新信息失败，请稍后重试。"))
                }
            }
        })
    }
}
