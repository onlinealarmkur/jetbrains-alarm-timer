package com.onlinealarmkur.jetbrains

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.util.Properties

class UrlsTest {
    @Test
    fun `constants match their pinned literal values`() {
        assertEquals("https://onlinealarmkur.com/en/", Urls.SITE_URL)
        assertEquals("https://onlinealarmkur.com/en/", Urls.ALARM_URL)
        assertEquals("https://onlinealarmkur.com/timer/en/", Urls.TIMER_URL)
        assertEquals("https://github.com/onlinealarmkur/jetbrains-alarm-timer", Urls.REPOSITORY_URL)
        assertEquals("https://github.com/onlinealarmkur/jetbrains-alarm-timer", Urls.DOCUMENTATION_URL)
        assertEquals("https://github.com/onlinealarmkur/jetbrains-alarm-timer/issues", Urls.ISSUES_URL)
    }

    @Test
    fun `every constant is a well formed https url with the expected host and trailing slash convention`() {
        val siteUrls = listOf(Urls.SITE_URL, Urls.ALARM_URL, Urls.TIMER_URL)
        val gitHubUrls = listOf(Urls.REPOSITORY_URL, Urls.DOCUMENTATION_URL, Urls.ISSUES_URL)

        (siteUrls + gitHubUrls).forEach { url ->
            assertFalse(url.any { it.isWhitespace() }, url)
            assertEquals("https", URI(url).scheme, url)
        }

        siteUrls.forEach { url ->
            assertEquals("onlinealarmkur.com", URI(url).host, url)
            assertTrue(url.endsWith("/"), "expected $url to end with '/'")
        }

        gitHubUrls.forEach { url ->
            assertEquals("github.com", URI(url).host, url)
            assertFalse(url.endsWith("/"), "expected $url not to end with '/'")
        }
    }

    @Test
    fun `site url matches the plugin homepage and repository url matches the gradle publish target`() {
        // plugin.xml lives under src/main/resources, so it is on the test classpath — same
        // approach PluginResourcesTest uses to read /META-INF/pluginIcon.svg from the same
        // directory.
        val pluginXml = resource("/META-INF/plugin.xml")
        val homepageUrl = Regex("""<idea-plugin url="([^"]*)"""").find(pluginXml)?.groupValues?.get(1)
        assertEquals(Urls.SITE_URL, homepageUrl, "plugin.xml <idea-plugin> homepage url")

        // gradle.properties lives at the repository root, outside src/main/resources, so it
        // is not a classpath resource. No established repo pattern reads files like this in
        // the existing test suite (searched for File(...)/Paths.get(...) usage under
        // src/test/kotlin and found none), so this reads it via a plain relative path from
        // the Gradle `test` task's working directory, which defaults to the project
        // directory since build.gradle.kts does not override it — unverified by execution
        // in this environment, so guard with an assumption: if that default ever changes
        // or differs on another machine, this check SKIPS instead of failing.
        val gradlePropertiesFile = File("gradle.properties")
        assumeTrue(gradlePropertiesFile.exists(), "gradle.properties not resolvable from the test working directory")
        val gradleProperties = Properties().apply {
            gradlePropertiesFile.inputStream().use(::load)
        }
        assertEquals(Urls.REPOSITORY_URL, gradleProperties.getProperty("pluginRepositoryUrl"))
    }

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull(stream, path)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
