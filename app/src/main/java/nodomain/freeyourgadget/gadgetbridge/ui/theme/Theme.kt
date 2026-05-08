package nodomain.freeyourgadget.gadgetbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// --- Colours ---
val Blue80   = Color(0xFF82B4FF)
val Blue40   = Color(0xFF1A6ECC)
val Indigo80 = Color(0xFFBBB4FF)
val Indigo40 = Color(0xFF4B3FCC)

val HeartRed   = Color(0xFFFF5252)
val StepGreen  = Color(0xFF4CAF50)
val SleepBlue  = Color(0xFF3F51B5)
val BatteryAmber = Color(0xFFFFC107)

private val DarkColorScheme = darkColorScheme(
    primary       = Blue80,
    secondary     = Indigo80,
    background    = Color(0xFF0F0F17),
    surface       = Color(0xFF1A1A28),
    surfaceVariant = Color(0xFF242436),
    onPrimary     = Color(0xFF003060),
    onSecondary   = Color(0xFF1E0A6E),
    onBackground  = Color(0xFFE6E1F5),
    onSurface     = Color(0xFFE6E1F5)
)

private val LightColorScheme = lightColorScheme(
    primary       = Blue40,
    secondary     = Indigo40,
    background    = Color(0xFFF5F5FF),
    surface       = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEEEF8),
    onPrimary     = Color.White,
    onSecondary   = Color.White,
    onBackground  = Color(0xFF1A1A30),
    onSurface     = Color(0xFF1A1A30)
)

@Composable
fun OmniBandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme    -> DarkColorScheme
        else         -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
