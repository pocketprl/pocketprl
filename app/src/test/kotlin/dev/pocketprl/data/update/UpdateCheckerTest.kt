package dev.pocketprl.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `compare orders dotted versions and tolerates a leading v and short segments`() {
        assertTrue(UpdateChecker.compare("2.3.2", "2.3.1") > 0)
        assertTrue(UpdateChecker.compare("2.0.0", "1.9.9") > 0)
        assertTrue(UpdateChecker.compare("1.0.0", "2.0.0") < 0)
        assertEquals(0, UpdateChecker.compare("v2.3.2", "2.3.2"))
        assertEquals(0, UpdateChecker.compare("1.1", "1.1.0"))
        assertTrue(UpdateChecker.compare("1.1.1", "1.1") > 0)
    }

    private val body = """
        [
          {
            "tag_name": "v2.2.0",
            "html_url": "https://github.com/pocketprl/pocketprl/releases/tag/v2.2.0",
            "prerelease": false,
            "published_at": "2026-09-19T10:00:00Z",
            "body": "## 2.2.0\n* In-app updates.",
            "assets": [
              { "name": "PocketPRL-2.2.0-mapping.txt.gz", "browser_download_url": "https://x/map", "size": 10 },
              { "name": "PocketPRL-2.2.0.apk", "browser_download_url": "https://x/app.apk", "size": 1000, "digest": "sha256:282B9E9703A959DEB383831F110295489457E1D468AAA2EECA9A08FC5C7E5C6F" },
              { "name": "SHA256SUMS.txt", "browser_download_url": "https://x/sums", "size": 5 }
            ]
          },
          {
            "tag_name": "v1.0.0",
            "html_url": "https://github.com/pocketprl/pocketprl/releases/tag/v1.0.0",
            "prerelease": true,
            "published_at": "2026-09-16T10:00:00Z",
            "body": "   ",
            "assets": []
          }
        ]
    """.trimIndent()

    @Test
    fun `parseReleasesJson selects the wallet APK and reads digest, date and prerelease`() {
        val list = UpdateChecker.parseReleasesJson(body)
        assertEquals(2, list.size)

        val r = list[0]
        assertEquals("2.2.0", r.version)
        assertEquals("PocketPRL-2.2.0.apk", r.apkName)
        assertEquals("https://x/app.apk", r.apkUrl)
        assertEquals(1000L, r.apkSize)
        assertEquals("282b9e9703a959deb383831f110295489457e1d468aaa2eeca9a08fc5c7e5c6f", r.sha256)
        assertEquals("2026-09-19T10:00:00Z", r.publishedAt)
        assertFalse(r.prerelease)
        assertTrue(r.body!!.contains("In-app updates"))

        val old = list[1]
        assertEquals("1.0.0", old.version)
        assertNull(old.apkName)
        assertTrue(old.prerelease)
        assertNull("whitespace-only body reads as absent", old.body)
    }

    @Test
    fun `an APK digest of the wrong length is ignored`() {
        val bad = """
            [{"tag_name":"v9.9.9","html_url":"u","assets":[
              {"name":"PocketPRL-9.9.9.apk","browser_download_url":"a","size":1,"digest":"sha256:deadbeef"}
            ]}]
        """.trimIndent()
        assertNull(UpdateChecker.parseReleasesJson(bad).single().sha256)
    }

    @Test
    fun `malformed JSON yields an empty list instead of throwing`() {
        assertTrue(UpdateChecker.parseReleasesJson("not json at all").isEmpty())
        assertTrue(UpdateChecker.parseReleasesJson("{}").isEmpty())
    }
}
