package site.chirudev.pdfutils.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import site.chirudev.pdfutils.home.HomeScreen
import site.chirudev.pdfutils.tools.DoneView
import site.chirudev.pdfutils.tools.OptionField
import site.chirudev.pdfutils.tools.Phase
import site.chirudev.pdfutils.tools.ProcessingView
import site.chirudev.pdfutils.tools.Tool
import site.chirudev.pdfutils.tools.Values
import site.chirudev.pdfutils.tools.toolBySlug
import site.chirudev.pdfutils.tools.tools
import site.chirudev.pdfutils.testing.fileMeta
import site.chirudev.pdfutils.testing.job
import site.chirudev.pdfutils.core.network.Job
import site.chirudev.pdfutils.ui.theme.PdfUtilsTheme

/** Renders the real composables on Robolectric so composition crashes are caught without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreensRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `home lists tools and search filters them`() {
        var opened: Tool? = null
        compose.setContent { PdfUtilsTheme { HomeScreen(onOpen = { opened = it }, contentPadding = PaddingValues()) } }

        compose.onNodeWithText("Merge PDF").assertIsDisplayed()
        compose.onNodeWithText("Search tools").performTextInput("unlock")
        compose.onNodeWithText("Unlock PDF").assertIsDisplayed().performClick()
        assertEquals("unlock-pdf", opened?.slug)
    }

    @Test fun `every tool's options render`() {
        var current by mutableStateOf(tools.first())
        compose.setContent {
            PdfUtilsTheme {
                val tool = current
                var values: Values by remember(tool) { mutableStateOf(tool.defaultValues()) }
                Column {
                    tool.fields.filter { it.showIf(values) }.forEach { f ->
                        OptionField(f, values, emptyMap(), { k, v -> values = values + (k to v) }, { _, _ -> })
                    }
                }
            }
        }
        tools.forEach { current = it; compose.waitForIdle() }
    }

    @Test fun `switching split mode reveals the matching fields`() {
        val split = toolBySlug("split-pdf")!!
        compose.setContent {
            PdfUtilsTheme {
                var values: Values by remember { mutableStateOf(split.defaultValues()) }
                Column { split.fields.filter { it.showIf(values) }.forEach { f -> OptionField(f, values, emptyMap(), { k, v -> values = values + (k to v) }, { _, _ -> }) } }
            }
        }
        compose.onNodeWithText("Ranges").assertIsDisplayed()
        compose.onNodeWithText("Fixed size").performClick()
        compose.onNodeWithText("Pages per file").assertIsDisplayed()
    }

    @Test fun `processing view renders`() {
        compose.setContent { PdfUtilsTheme { ProcessingView(toolBySlug("compress-pdf")!!, job(Job.STATUS_QUEUED)) } }
        compose.onNodeWithText("Waiting in line…").assertIsDisplayed()
    }

    @Test fun `done view shows savings and actions`() {
        val out = fileMeta("o1", name = "small.pdf", size = 250, kind = "output").copy(expiresAt = "2026-10-25T12:00:00Z")
        compose.setContent {
            PdfUtilsTheme {
                DoneView(toolBySlug("compress-pdf")!!, Phase.Done(out, inputBytes = 1000), signedIn = false, busy = null, onAction = {}, onRestart = {}, onContinue = {}, onSignUp = {}, onHistory = {})
            }
        }
        compose.onNodeWithText("Your file is ready!").assertIsDisplayed()
        compose.onNodeWithText("75%").assertIsDisplayed()
        compose.onNodeWithText("Save PDF").assertIsDisplayed()
    }
}
