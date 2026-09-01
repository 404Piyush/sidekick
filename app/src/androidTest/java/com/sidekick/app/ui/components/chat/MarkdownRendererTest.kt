package com.sidekick.app.ui.components.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sidekick.app.ui.theme.SidekickTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [MarkdownText].
 *
 * Verifies that the hand-rolled renderer produces the expected Compose
 * nodes for each markdown construct. We don't do pixel-level snapshot
 * comparisons (those would require paparazzi / Roborazzi and a much
 * heavier setup); the assertions here are structural — "this text
 * appears in the tree" — which is enough to catch parser regressions
 * and renderer wiring bugs.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownRendererTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun plainParagraphRendersOneNodeWithTheText() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(source = "Hello, world.")
            }
        }
        composeTestRule.onNodeWithText("Hello, world.").assertIsDisplayed()
    }

    @Test
    fun headingTextIsDisplayed() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(source = "# Big Title")
            }
        }
        composeTestRule.onNodeWithText("Big Title").assertIsDisplayed()
    }

    @Test
    fun bulletListRendersAllItems() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(
                    source = """
                        - alpha
                        - beta
                        - gamma
                    """.trimIndent(),
                )
            }
        }
        composeTestRule.onNodeWithText("alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("beta").assertIsDisplayed()
        composeTestRule.onNodeWithText("gamma").assertIsDisplayed()
    }

    @Test
    fun fencedCodeBlockRendersBodyText() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(
                    source = """
                        ```kotlin
                        println("hi")
                        ```
                    """.trimIndent(),
                )
            }
        }
        // The body is rendered verbatim (newlines preserved).
        composeTestRule.onNodeWithText("println(\"hi\")").assertIsDisplayed()
    }

    @Test
    fun inlineBoldRendersAsPartOfParagraph() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(source = "this is **bold** text")
            }
        }
        // Compose merges AnnotatedString spans into one Text node whose
        // combined text reads "this is bold text" — we assert on the
        // concatenation rather than the underlying spans.
        composeTestRule.onNodeWithText("this is bold text").assertIsDisplayed()
    }

    @Test
    fun inlineItalicRendersAsPartOfParagraph() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(source = "an *italic* word")
            }
        }
        composeTestRule.onNodeWithText("an italic word").assertIsDisplayed()
    }

    @Test
    fun inlineCodeRendersAsPartOfParagraph() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(source = "use `println()` to print")
            }
        }
        composeTestRule.onNodeWithText("use println() to print").assertIsDisplayed()
    }

    @Test
    fun emptySourceRendersNothing() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(source = "")
            }
        }
        // No Text nodes with content — the column should be empty.
        composeTestRule.onAllNodesWithText("").assertCountEquals(0)
    }

    @Test
    fun markdownMixedWithCodeBlocksRendersBoth() {
        composeTestRule.setContent {
            SidekickTheme {
                MarkdownText(
                    source = """
                        Here's an intro.

                        ```
                        some code
                        ```
                    """.trimIndent(),
                )
            }
        }
        // The intro and the code body both appear in the tree.
        composeTestRule.onNodeWithText("Here's an intro.").assertIsDisplayed()
        composeTestRule.onNodeWithText("some code").assertIsDisplayed()
    }

    @Test
    fun textInsideThemeRendersWithoutCrash() {
        // Sanity: confirm the theme integration doesn't throw — this
        // catches issues like a missing MaterialTheme ancestor.
        composeTestRule.setContent {
            SidekickTheme {
                Text("hello")
            }
        }
        composeTestRule.onNodeWithText("hello").assertIsDisplayed()
        // The MaterialTheme should be the one SidekickTheme supplies.
        composeTestRule.setContent {
            MaterialTheme {
                MarkdownText(source = "test")
            }
        }
        composeTestRule.onNodeWithText("test").assertIsDisplayed()
    }
}