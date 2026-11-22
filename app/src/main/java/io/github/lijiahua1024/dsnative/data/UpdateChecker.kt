package io.github.lijiahua1024.dsnative.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class UpdateAvailable(
        val version: String,
        val releaseUrl: String,
        val downloadUrl: String?,
    ) : UpdateCheckResult()
}

object UpdateChecker {
    private const val REPOSITORY = "LiJiaHua1024/deepseek-app-replica-native"
    private const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$REPOSITORY/releases"
    private val SEMVER_RE = Regex("^v?(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:-.+)?$")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun compareSemanticVersions(candidate: String, current: String): Int? {
        val a = SEMVER_RE.matchEntire(candidate.trim()) ?: return null
        val b = SEMVER_RE.matchEntire(current.trim()) ?: return null
        for (i in 1..3) {
            val diff = a.groupValues[i].toInt().compareTo(b.groupValues[i].toInt())
            if (diff != 0) return diff
        }
        return 0
    }

    fun parseLatestReleaseJson(body: String, installedVersion: String): UpdateCheckResult {
        val obj = Json.parseToJsonElement(body).jsonObject
        val draft = obj["draft"]?.jsonPrimitive?.booleanOrNull == true
        val prerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true
        if (draft || prerelease) {
            return UpdateCheckResult.UpToDate
        }
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val version = tag.removePrefix("v")
        val cmp = compareSemanticVersions(version, installedVersion)
            ?: throw IOException("Invalid release version: $tag")
        if (cmp <= 0) return UpdateCheckResult.UpToDate

        val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("https://") }
            ?: RELEASES_PAGE_URL
        var apkUrl: String? = null
        val assets = runCatching { obj["assets"]?.jsonArray }.getOrNull()
        if (assets != null) {
            for (elem in assets) {
                val asset = runCatching { elem.jsonObject }.getOrNull() ?: continue
                val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val state = asset["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (name.endsWith(".apk", ignoreCase = true) &&
                    (state.isEmpty() || state == "uploaded") &&
                    url.startsWith("https://github.com/$REPOSITORY/releases/download/")
                ) {
                    apkUrl = url
                    break
                }
            }
        }
        return UpdateCheckResult.UpdateAvailable(
            version = version,
            releaseUrl = htmlUrl,
            downloadUrl = apkUrl,
        )
    }

    suspend fun checkLatestRelease(installedVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "deepseek-app-replica-native")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext UpdateCheckResult.UpToDate
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response")
            parseLatestReleaseJson(body, installedVersion)
        }
    }
}
