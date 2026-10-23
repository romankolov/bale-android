package ai.bale.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

/** Exercise the real manifest, Hilt graph, Keystore/Room initialization and UI. */
class AppLaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshInstallLaunchesSignIn() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("A little closer.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A little closer.").assertIsDisplayed()
        compose.onNodeWithText("Send verification code").performScrollTo().assertIsDisplayed()
    }
}
