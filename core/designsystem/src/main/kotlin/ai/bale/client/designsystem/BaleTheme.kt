package ai.bale.client.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF006C53),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF9DF4CE),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD7E8DF),
    surface = androidx.compose.ui.graphics.Color(0xFFF6FBF7),
)
private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF80D7B3),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF00513D),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF354B42),
    surface = androidx.compose.ui.graphics.Color(0xFF0E1511),
)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BaleTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
@Composable
fun Notice(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
