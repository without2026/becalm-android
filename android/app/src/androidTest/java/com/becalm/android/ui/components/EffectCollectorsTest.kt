package com.becalm.android.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EffectCollectorsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun collect_flow_effect_delivers_emitted_values_to_the_ui_effect_handler() {
        val effects = MutableSharedFlow<String>(extraBufferCapacity = 1)
        var delivered: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                CollectFlowEffect(effects) { effect ->
                    delivered = effect
                }
            }
        }

        effects.tryEmit("navigate-settings")

        composeTestRule.runOnIdle {
            assertEquals("navigate-settings", delivered)
        }
    }

    @Test
    fun handle_snackbar_message_shows_snackbar_and_consumes_the_message() {
        composeTestRule.mainClock.autoAdvance = false

        var message by mutableStateOf<String?>(null)
        var consumed = 0

        composeTestRule.setContent {
            BecalmTheme {
                val hostState = remember { SnackbarHostState() }
                SnackbarHost(hostState = hostState)
                HandleSnackbarMessage(
                    message = message,
                    snackbarHostState = hostState,
                    onConsumed = {
                        consumed += 1
                        message = null
                    },
                )
            }
        }

        composeTestRule.runOnIdle {
            message = "저장되었습니다"
        }

        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertEquals(1, consumed)
            assertNull(message)
        }
    }

    @Test
    fun handle_snackbar_message_stays_idle_when_message_is_null() {
        composeTestRule.setContent {
            BecalmTheme {
                val hostState = remember { SnackbarHostState() }
                SnackbarHost(hostState = hostState)
                HandleSnackbarMessage(
                    message = null,
                    snackbarHostState = hostState,
                    onConsumed = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText("저장되었습니다").assertCountEquals(0)
    }
}
