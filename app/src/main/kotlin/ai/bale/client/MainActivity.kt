package ai.bale.client

import ai.bale.client.designsystem.AppLocaleStore
import ai.bale.client.designsystem.BaleTheme
import ai.bale.client.domain.AppThemeMode
import ai.bale.client.domain.AuthRepository
import ai.bale.client.domain.MessengerRepository
import ai.bale.client.feature.auth.AuthScreen
import ai.bale.client.feature.messaging.MessengerScreen
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: SessionViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleStore.forBase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val ready by model.ready.collectAsStateWithLifecycle()
            val failed by model.failed.collectAsStateWithLifecycle()
            val account by model.account.collectAsStateWithLifecycle()
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (preferences.themeMode) {
                AppThemeMode.Dark -> true
                AppThemeMode.Light -> false
                AppThemeMode.System -> systemDark
            }
            BaleTheme(dark = dark, dynamicColor = preferences.dynamicColor) {
                if (!ready) Surface(Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        if (failed) Text(stringResource(ai.bale.client.core.designsystem.R.string.startup_failed))
                        else CircularProgressIndicator()
                    }
                } else if (account == null) AuthScreen() else MessengerScreen()
            }
        }
    }
}
@HiltViewModel
class SessionViewModel @Inject constructor(auth: AuthRepository, messenger: MessengerRepository) : ViewModel() {
    val account = auth.account
    val preferences = messenger.preferences
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val failed = mutableFailed.asStateFlow()
    init {
        viewModelScope.launch {
            try { auth.restore(); mutableReady.value = true } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { mutableFailed.value = true }
        }
    }
}
