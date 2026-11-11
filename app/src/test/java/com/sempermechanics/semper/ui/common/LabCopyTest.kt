package com.sempermechanics.semper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Copy only this app has, for a first-semester student (readiness MT-AE3-002):
 * TalkBack reads the logo as the app's full name, an abbreviation is spelled out,
 * and the images after the reference are never "deformed photos" beside "deformed frames".
 */
class LabCopyTest {

    private val xml by lazy { File("src/main/res/values/strings.xml").readText() }

    private val strings: Map<String, String> by lazy {
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The lines of strings.xml (plurals' items included) that match [regex]. */
    private fun offenders(regex: Regex): List<String> =
        xml.lines().filter { regex.containsMatchIn(it) }.map { it.trim() }

    @Test
    fun `the logo is read as the app's name`() {
        val appName = strings.getValue("app_name")
        for (layout in listOf("activity_home", "activity_splash")) {
            val layoutXml = File("src/main/res/layout/$layout.xml").readText()
            val named = Regex("""contentDescription="@string/(app_name\w*)"""").findAll(layoutXml)
                .map { strings.getValue(it.groupValues[1]) }.toList()
            assertTrue("$layout names its logo", named.isNotEmpty())
            assertEquals(layout, named.map { appName }, named)
        }
    }

    /** The line under a disabled Next is a sentence of its own, so it opens with a capital. */
    @Test
    fun `a Next reason starts with a capital`() {
        val plurals = Regex("""<plurals name="(next_reason_[^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .flatMap { block ->
                Regex("""<item[^>]*>(.*?)</item>""").findAll(block.groupValues[2])
                    .map { block.groupValues[1] to it.groupValues[1] }
            }
        val reasons = strings.filterKeys { it.startsWith("next_reason_") }.toList() + plurals
        assertTrue("found the reasons", reasons.size >= 8)
        // A plural opens with its count ("1 photo has no load…"), which has no case.
        val lowerCase = reasons.filter { (_, text) -> text.first { !it.isWhitespace() && it != '%' }.isLowerCase() }
        assertEquals(emptyList<Pair<String, String>>(), lowerCase)
    }

    @Test
    fun `no unexplained abbreviation`() {
        assertEquals(emptyList<String>(), offenders(Regex("""\bTAs?\b""")))
    }

    @Test
    fun `the images after the reference are deformed frames`() {
        assertEquals(emptyList<String>(), offenders(Regex("""deformed photos?\b""")))
    }
}
