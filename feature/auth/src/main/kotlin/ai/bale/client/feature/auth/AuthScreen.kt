package ai.bale.client.feature.auth

import ai.bale.client.core.designsystem.R
import ai.bale.client.designsystem.Notice
import ai.bale.client.domain.AuthStep
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AuthScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember(state.step) { mutableStateOf("") }
    val (title, subtitle, label) = when (state.step) {
        AuthStep.Phone -> Triple(R.string.welcome_title, R.string.welcome_subtitle, R.string.phone_label)
        AuthStep.Code -> Triple(R.string.code_title, R.string.code_subtitle, R.string.code_label)
        AuthStep.Password -> Triple(R.string.password_title, R.string.password_subtitle, R.string.password_label)
        AuthStep.Registration -> Triple(R.string.registration_title, R.string.registration_subtitle, R.string.name_label)
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("ب", fontSize = 48.sp, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(title), style = MaterialTheme.typography.displayMedium)
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = input, onValueChange = { if (it.length <= 256) input = it },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
                    shape = RoundedCornerShape(20.dp), label = { Text(stringResource(label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = when (state.step) {
                        AuthStep.Phone -> KeyboardType.Phone
                        AuthStep.Code -> KeyboardType.NumberPassword
                        AuthStep.Password -> KeyboardType.Password
                        AuthStep.Registration -> KeyboardType.Text
                    }),
                    visualTransformation = if (state.step == AuthStep.Password || state.step == AuthStep.Code) PasswordVisualTransformation() else VisualTransformation.None,
                )
                if (state.error || state.invalidPhone) Notice(stringResource(if (state.invalidPhone) R.string.invalid_phone else R.string.auth_failed))
                Button(onClick = { val submitted = input; input = ""; viewModel.submit(submitted) }, enabled = !state.busy && input.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text(stringResource(if (state.step == AuthStep.Phone) R.string.send_code else R.string.continue_action))
                }
                if (state.step != AuthStep.Phone) TextButton(onClick = { input = ""; viewModel.reset() }, enabled = !state.busy) { Text(stringResource(R.string.change_number)) }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.unofficial_notice), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
