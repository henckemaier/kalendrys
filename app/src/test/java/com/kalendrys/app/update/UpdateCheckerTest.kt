package com.kalendrys.app.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Lógica pura do atualizador: comparação de versão (SemVer) e extração do asset .apk do JSON da
 * GitHub Releases API. Robolectric só para ter o org.json real (o stub do android.jar lança).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    @Test
    fun `release mais novo vira Available com a url do apk`() {
        val json = release(tag = "v1.1.0", apk = "https://example.com/app/kalendrys-1.1.0.apk")
        val result = UpdateChecker.evaluate(json, currentVersionName = "1.0")
        assertTrue(result is UpdateCheck.Available)
        result as UpdateCheck.Available
        assertEquals("1.1.0", result.versionName)
        assertEquals("https://example.com/app/kalendrys-1.1.0.apk", result.apkUrl)
    }

    @Test
    fun `mesma versao vira UpToDate`() {
        assertEquals(UpdateCheck.UpToDate, UpdateChecker.evaluate(release("v1.0.0", "https://x/a.apk"), "1.0.0"))
    }

    @Test
    fun `versao local mais nova vira UpToDate`() {
        assertEquals(UpdateCheck.UpToDate, UpdateChecker.evaluate(release("v1.0.0", "https://x/a.apk"), "1.2"))
    }

    @Test
    fun `sem asset apk vira MalformedResponse`() {
        assertEquals(UpdateCheck.MalformedResponse, UpdateChecker.evaluate(release("v2.0.0", "https://x/app.txt"), "1.0"))
    }

    @Test
    fun `json invalido vira MalformedResponse`() {
        assertEquals(UpdateCheck.MalformedResponse, UpdateChecker.evaluate("isto não é json", "1.0"))
    }

    @Test
    fun `tag sem numero vira MalformedResponse`() {
        assertEquals(UpdateCheck.MalformedResponse, UpdateChecker.evaluate(release("latest", "https://x/a.apk"), "1.0"))
    }

    @Test
    fun `sem tag_name vira MalformedResponse`() {
        assertEquals(UpdateCheck.MalformedResponse, UpdateChecker.evaluate("""{"assets":[{"browser_download_url":"https://x/a.apk"}]}""", "1.0"))
    }

    @Test
    fun `escolhe o primeiro asset que termina em apk`() {
        val json = """
            {"tag_name":"v1.1","assets":[
                {"browser_download_url":"https://x/notas.txt"},
                {"browser_download_url":"https://x/kalendrys.apk"}
            ]}
        """.trimIndent()
        val result = UpdateChecker.evaluate(json, "1.0") as UpdateCheck.Available
        assertEquals("https://x/kalendrys.apk", result.apkUrl)
    }

    @Test
    fun `semver compara por campo, nao lexicografico`() {
        assertTrue(Semver.parse("v1.10.0")!! > Semver.parse("1.9.0")!!)
        assertTrue(Semver.parse("2.0")!! > Semver.parse("1.9.9")!!)
        assertEquals(0, Semver.parse("1.0")!!.compareTo(Semver.parse("1.0.0")!!))
    }

    @Test
    fun `semver ignora sufixo de pre-release`() {
        assertEquals(Semver.parse("1.2.3"), Semver.parse("v1.2.3-rc1"))
    }

    private fun release(tag: String, apk: String): String =
        """{"tag_name":"$tag","assets":[{"browser_download_url":"$apk"}]}"""
}
