package ai.bale.client.feature.auth

import ai.bale.client.domain.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(val step: AuthStep = AuthStep.Phone, val busy: Boolean = false, val error: Boolean = false, val invalidPhone: Boolean = false)
@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state = mutableState.asStateFlow()
    // OTP/password/transaction deliberately never saved to SavedStateHandle or disk.
    private var challenge: AuthChallenge? = null
    fun submit(value: String) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = false, invalidPhone = false) }
        viewModelScope.launch {
            try {
                challenge = if (challenge == null) repository.start(value) else repository.verify(challenge!!, value)
                mutableState.value = AuthUiState(step = challenge?.step ?: AuthStep.Phone)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: IllegalArgumentException) { mutableState.update { it.copy(busy = false, invalidPhone = true) } } catch (_: Exception) { mutableState.update { it.copy(busy = false, error = true) } }
        }
    }
    fun reset() { if (!state.value.busy) { challenge = null; mutableState.value = AuthUiState() } }
}
