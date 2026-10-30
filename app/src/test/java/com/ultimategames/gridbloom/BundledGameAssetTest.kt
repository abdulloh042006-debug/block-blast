package com.ultimategames.gridbloom

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract between the Kotlin host and the bundled web game, without needing a device:
 * the page must be bundled, self-contained, and every bridge method the page calls must exist in
 * MainActivity (R8 would otherwise strip or the page would silently fail).
 */
class BundledGameAssetTest {

    // Gradle runs unit tests with the module directory (app/) as the working directory.
    private val page = File("src/main/assets/gridbloom.html")
    private val activity = File("src/main/java/com/ultimategames/gridbloom/ui/MainActivity.kt")

    private val bridgeMethods = listOf(
        "isRewardedReady", "requestRescue", "beforeNewGame", "onGameOver", "openPrivacy"
    )

    @Test
    fun gamePageIsBundled() {
        assertTrue("assets/gridbloom.html is missing", page.exists())
        assertTrue(page.readText().contains("<title>Gridbloom</title>"))
    }

    @Test
    fun gamePageIsSelfContained() {
        val html = page.readText()
        assertTrue("page must not load remote scripts", !Regex("""<script[^>]+src=""").containsMatchIn(html))
        assertTrue("page must not call remote hosts", !html.contains("http://") && !html.contains("https://"))
    }

    @Test
    fun everyBridgeMethodTheKotlinSideExposesIsKnownToThePage() {
        val html = page.readText()
        for (name in bridgeMethods) {
            assertTrue("page does not use bridge method $name", html.contains(name))
        }
    }

    @Test
    fun everyBridgeMethodIsAnnotatedInTheActivity() {
        val source = activity.readText()
        for (name in bridgeMethods) {
            val pattern = Regex("""@JavascriptInterface\s+fun $name\(""")
            assertTrue("MainActivity does not expose $name", pattern.containsMatchIn(source))
        }
        val annotated = Regex("""@JavascriptInterface""").findAll(source).count()
        assertEquals("unexpected extra exposed method", bridgeMethods.size, annotated)
    }

    @Test
    fun pageCallbacksExistOnBothSides() {
        val html = page.readText()
        val source = activity.readText()
        assertTrue(html.contains("rescueResult") && source.contains("rescueResult"))
        assertTrue(html.contains("proceedNewGame") && source.contains("proceedNewGame"))
    }
}
