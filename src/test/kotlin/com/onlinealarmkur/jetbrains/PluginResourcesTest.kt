package com.onlinealarmkur.jetbrains

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

class PluginResourcesTest {
    @Test
    fun `marketplace icons are compact native 40 pixel SVG variants`() {
        listOf("/META-INF/pluginIcon.svg", "/META-INF/pluginIcon_dark.svg").forEach { path ->
            val svg = resource(path)

            assertTrue(svg.contains("""width="40""""), path)
            assertTrue(svg.contains("""height="40""""), path)
            assertTrue(svg.contains("""viewBox="0 0 40 40""""), path)
            assertTrue(svg.toByteArray().size < 4_096, "$path should remain compact vector artwork.")
            assertFalse(svg.contains("<image"), "$path must not embed a raster image.")
            assertFalse(svg.contains("data:"), "$path must not embed encoded image data.")
        }
    }

    @Test
    fun `marketplace logo is a padded analog alarm with readable hands`() {
        listOf("/META-INF/pluginIcon.svg", "/META-INF/pluginIcon_dark.svg").forEach { path ->
            val svg = resource(path)

            assertTrue(
                svg.contains("""id="alarm-head" x="13.25" y="2.6" width="13.5" height="4.25"""),
                "$path must keep the alarm head balanced with the body",
            )
            assertFalse(svg.contains("alarm-head-shadow"), "$path must stay clear at 40 pixels")
            assertTrue(svg.contains("""id="body" x="3" y="5" width="34" height="32"""), path)
            assertTrue(svg.contains("""id="dial" cx="20" cy="22" r="13"""), path)
            assertTrue(svg.contains("""id="hour-hand"""), path)
            assertTrue(svg.contains("""stroke-width="1.9"""), "$path must not use a hairline hour hand")
            assertTrue(svg.contains("""id="minute-hand"""), path)
            assertTrue(svg.contains("""stroke-width="1.7"""), "$path must not use a hairline minute hand")
            assertTrue(svg.contains("""id="hub" cx="20" cy="22" r="1.55"""), path)
            assertFalse(svg.contains("<text"), "$path must not depend on font rendering.")
        }
    }

    @Test
    fun `tool window icons have matching native light and dark SVG variants`() {
        listOf("/icons/alarmTimer.svg", "/icons/alarmTimer_dark.svg").forEach { path ->
            val svg = resource(path)

            assertTrue(svg.contains("""width="16""""), path)
            assertTrue(svg.contains("""height="16""""), path)
            assertTrue(svg.contains("""viewBox="0 0 16 16""""), path)
            assertTrue(svg.toByteArray().size < 2_048, "$path should remain compact vector artwork.")
            assertFalse(svg.contains("<image"), "$path must not embed a raster image.")
            assertTrue(svg.contains("""id="alarm-head"""), "$path must preserve the alarm silhouette")
            assertTrue(svg.contains("""id="hour-hand"""), "$path must preserve the analog dial")
            assertTrue(svg.contains("""stroke-width="1.4"""), "$path must keep the hour hand legible")
            assertTrue(svg.contains("""id="minute-hand"""), "$path must preserve the analog dial")
            assertTrue(svg.contains("""stroke-width="1.25"""), "$path must keep the minute hand legible")
        }
    }

    @Test
    fun `new UI tool window icons include native regular and compact variants`() {
        mapOf(
            "/icons/expui/alarmTimer.svg" to Triple("16", "#6C707E", "light compact"),
            "/icons/expui/alarmTimer_dark.svg" to Triple("16", "#CED0D6", "dark compact"),
            "/icons/expui/alarmTimer@20x20.svg" to Triple("20", "#6C707E", "light regular"),
            "/icons/expui/alarmTimer@20x20_dark.svg" to Triple("20", "#CED0D6", "dark regular"),
        ).forEach { (path, expected) ->
            val (size, color, variant) = expected
            val svg = resource(path)

            assertTrue(svg.contains("""width="$size"""), "$path must have native $variant width")
            assertTrue(svg.contains("""height="$size"""), "$path must have native $variant height")
            assertTrue(svg.contains("""viewBox="0 0 $size $size"""), path)
            assertTrue(svg.contains(color), "$path must use the prescribed JetBrains New UI color")
            assertTrue(svg.toByteArray().size < 3_072, "$path should remain compact vector artwork.")
            assertFalse(svg.contains("<image"), "$path must not embed a raster image.")
            if (size == "16") {
                assertTrue(svg.contains("""id="alarm-head" x="6.35" y="1"""), "$path must preserve the alarm silhouette")
                assertTrue(svg.contains("""stroke-width="1.4"""), "$path must keep the hour hand legible")
            } else {
                assertTrue(svg.contains("""id="alarm-head" x="7.8" y="1.25"""), "$path must use a native 20px alarm silhouette")
                assertTrue(svg.contains("""stroke-width="1.75"""), "$path must keep the 20px hour hand legible")
            }
        }
    }

    @Test
    fun `new UI icon mapping is registered and points to the tool window icon`() {
        val pluginXml = resource("/META-INF/plugin.xml")
        val mappings = resource("/AlarmTimerIconMappings.json")

        assertTrue(
            Regex("""<iconMapper\s+mappingFile="AlarmTimerIconMappings\.json"\s*/>""").containsMatchIn(pluginXml),
        )
        assertTrue(mappings.contains(""""expui""""))
        assertTrue(mappings.contains(""""alarmTimer.svg": "icons/alarmTimer.svg"""))
    }

    @Test
    fun `processed plugin version resource matches the Gradle version without a placeholder`() {
        val contents = resource("/com/onlinealarmkur/jetbrains/plugin-version.properties")
        val properties = Properties().apply {
            contents.byteInputStream(Charsets.ISO_8859_1).use(::load)
        }
        val version = properties.getProperty("version")

        assertNotNull(version)
        assertEquals(setOf("version"), properties.stringPropertyNames())
        assertTrue(
            Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matches(version!!),
            version,
        )
        assertEquals("version=$version\n", contents)
        assertFalse(contents.any { it == '$' || it == '{' }, contents)

        val gradlePropertiesFile = File("gradle.properties")
        assumeTrue(
            gradlePropertiesFile.exists(),
            "gradle.properties not resolvable from the test working directory",
        )
        val gradleProperties = Properties().apply {
            gradlePropertiesFile.inputStream().use(::load)
        }
        assertEquals(gradleProperties.getProperty("pluginVersion"), version)
    }

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull(stream, path)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
