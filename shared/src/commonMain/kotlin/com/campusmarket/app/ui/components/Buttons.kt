package com.campusmarket.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.theme.CampusTheme

private val ButtonMinHeight = 48.dp
private val ButtonPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)

/**
 * Main call to action: a pill filled with the Golden Hour action gradient and white label. [loading] keeps the
 * gradient, shows a spinner and ignores taps; disabled is a quiet grey pill. Ripple and a slight press shade come
 * on top of the gradient.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val branded = enabled || loading
    val shape = CampusTheme.pill
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ButtonMinHeight)
            .then(if (branded) Modifier.background(CampusTheme.actionGradient, shape) else Modifier)
            .then(if (branded && pressed) Modifier.background(Color.Black.copy(alpha = 0.10f), shape) else Modifier),
        enabled = enabled && !loading,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = if (loading) Color.Transparent else scheme.onSurface.copy(alpha = 0.10f),
            disabledContentColor = if (loading) Color.White else scheme.onSurface.copy(alpha = 0.45f),
        ),
        // No shadow: the gradient is drawn behind a transparent container, and a shadow would outline that instead.
        elevation = null,
        contentPadding = ButtonPadding,
        interactionSource = interaction,
    ) {
        ButtonContent(text, loading, icon)
    }
}

/** Secondary action next to a [PrimaryButton]: a ghost pill with a 1.5 dp ring in the primary colour. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val active = enabled && !loading
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonMinHeight),
        enabled = active,
        shape = CampusTheme.pill,
        border = BorderStroke(1.5.dp, if (active || loading) scheme.primary else scheme.onSurface.copy(alpha = 0.16f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = scheme.primary,
            disabledContentColor = if (loading) scheme.primary else scheme.onSurface.copy(alpha = 0.45f),
        ),
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, loading, icon)
    }
}

/** Irreversible actions (delete listing, cancel order, delete account). Pair with [ConfirmDialog]. */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonMinHeight),
        enabled = enabled && !loading,
        shape = CampusTheme.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = scheme.onError,
            disabledContainerColor = if (loading) scheme.error else scheme.onSurface.copy(alpha = 0.10f),
            disabledContentColor = if (loading) scheme.onError else scheme.onSurface.copy(alpha = 0.45f),
        ),
        elevation = null,
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, loading, icon)
    }
}

@Composable
private fun ButtonContent(text: String, loading: Boolean, icon: ImageVector?) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = LocalContentColor.current,
        )
        Spacer(Modifier.width(10.dp))
    } else if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text)
}
