package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.UpdateCheckResult
import io.github.lijiahua1024.dsnative.data.UpdateChecker
import io.github.lijiahua1024.dsnative.data.normalizeAccountProfile
import io.github.lijiahua1024.dsnative.data.AccountProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun semanticVersionComparisonHandlesMajorMinorPatch() {
        assertTrue((UpdateChecker.compareSemanticVersions("0.5.0", "0.4.0") ?: 0) > 0)
        assertTrue((UpdateChecker.compareSemanticVersions("v1.0.0", "0.9.9") ?: 0) > 0)
        assertEquals(0, UpdateChecker.compareSemanticVersions("v0.4.0", "0.4.0"))
        assertEquals(0, UpdateChecker.compareSemanticVersions("v0.1.0", "0.1.0"))
        assertTrue((UpdateChecker.compareSemanticVersions("0.2.0", "0.1.0") ?: 0) > 0)
        assertTrue((UpdateChecker.compareSemanticVersions("0.3.9", "0.4.0") ?: 0) < 0)
        assertNull(UpdateChecker.compareSemanticVersions("invalid", "0.4.0"))
    }

    @Test
    fun parsesNewerGithubReleaseWithVerifiedApkAsset() {
        val json = """
            {
              "tag_name": "v0.5.0",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/LiJiaHua1024/deepseek-app-replica-native/releases/tag/v0.5.0",
              "assets": [
                {
                  "name": "dsnative-v0.5.0.apk",
                  "state": "uploaded",
                  "browser_download_url": "https://github.com/LiJiaHua1024/deepseek-app-replica-native/releases/download/v0.5.0/dsnative-v0.5.0.apk"
                }
              ]
            }
        """.trimIndent()
        val result = UpdateChecker.parseLatestReleaseJson(json, "0.4.0")
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals("0.5.0", available.version)
        assertEquals(
            "https://github.com/LiJiaHua1024/deepseek-app-replica-native/releases/download/v0.5.0/dsnative-v0.5.0.apk",
            available.downloadUrl,
        )
    }

    @Test
    fun treatsSameOrPrereleaseAsUpToDate() {
        val same = """{"tag_name":"v0.4.0","draft":false,"prerelease":false}"""
        assertEquals(UpdateCheckResult.UpToDate, UpdateChecker.parseLatestReleaseJson(same, "0.4.0"))

        val pre = """{"tag_name":"v0.9.0","draft":false,"prerelease":true}"""
        assertEquals(UpdateCheckResult.UpToDate, UpdateChecker.parseLatestReleaseJson(pre, "0.4.0"))
    }

    @Test
    fun accountProfileNormalizationTruncatesLongUsernamesAndRejectsInvalidAvatars() {
        val longName = "x".repeat(60)
        val norm = normalizeAccountProfile(AccountProfile("  $longName  ", "javascript:alert(1)"))
        assertEquals(40, norm.username.length)
        assertNull(norm.avatarDataUrl)

        val validAvatar = normalizeAccountProfile(AccountProfile("Alice", "data:image/webp;base64,AAAA"))
        assertEquals("Alice", validAvatar.username)
        assertEquals("data:image/webp;base64,AAAA", validAvatar.avatarDataUrl)
    }
}
