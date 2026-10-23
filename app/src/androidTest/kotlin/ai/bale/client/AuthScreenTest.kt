package ai.bale.client

import ai.bale.client.designsystem.BaleTheme
import ai.bale.client.domain.*
import ai.bale.client.feature.auth.AuthScreen
import ai.bale.client.feature.auth.AuthViewModel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class AuthScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun phoneAdvancesToVerification() {
        val repository = object : AuthRepository {
            override val account = MutableStateFlow<Account?>(null)
            override suspend fun restore() = Unit
            override suspend fun start(phone: String) = AuthChallenge("fixture", AuthStep.Code)
            override suspend fun verify(challenge: AuthChallenge, value: String): AuthChallenge? = null
            override suspend fun signOut() = Unit
        }
        val model = AuthViewModel(repository)
        compose.setContent { BaleTheme { AuthScreen(model) } }
        compose.onNodeWithText("Phone number, including country code").performTextInput("+989123456789")
        compose.onNodeWithText("Send verification code").performScrollTo().performClick()
        compose.onNodeWithText("Check your messages").performScrollTo().assertIsDisplayed()
    }
}
