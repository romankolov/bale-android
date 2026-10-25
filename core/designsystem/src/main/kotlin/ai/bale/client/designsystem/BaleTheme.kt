package ai.bale.client.designsystem

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Keeps the chosen app language across configuration changes without AppCompat.
 * [android.app.Activity.attachBaseContext] wraps the base context with [forBase].
 */
object AppLocaleStore {
    private const val PREFS = "bale_rc_prefs"
    private const val KEY = "app_locale_tag"

    /** Empty tag means "follow the system language". */
    fun tag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun setTag(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    fun forBase(base: Context): Context {
        val tag = tag(base)
        if (tag.isBlank()) return base
        val locale = java.util.Locale.forLanguageTag(tag)
        java.util.Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        // setLayoutDirection derives RTL/LTR from the locale, so Arabic/Persian flip the layout.
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C53),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9DF4CE),
    secondaryContainer = Color(0xFFD7E8DF),
    surface = Color(0xFFF6FBF7),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D7B3),
    primaryContainer = Color(0xFF00513D),
    secondaryContainer = Color(0xFF354B42),
    surface = Color(0xFF0E1511),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BaleTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        DarkColors
    } else {
        LightColors
    }
    MaterialExpressiveTheme(colorScheme = colors, content = content)
}

@Composable
fun Notice(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
