package com.campusmarket.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.theme.Spacing

/**
 * The app's text field: outlined, full width, with a field error (red, replaces the supporting text) and an
 * optional character counter when [maxLength] is set (input is truncated to it). [contentType] tells password
 * managers / autofill what goes here (`ContentType.EmailAddress`, `ContentType.Password`, …).
 *
 * Show server validation with `error = fieldErrors["title"] ?: localError`.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supportingText: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    maxLength: Int? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    contentType: ContentType? = null,
) {
    val showSupporting = error != null || supportingText != null || maxLength != null
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(if (maxLength != null) it.take(maxLength) else it) },
        modifier = modifier
            .fillMaxWidth()
            .then(if (contentType != null) Modifier.semantics { this.contentType = contentType } else Modifier),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = error != null,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        // Card-coloured fill (white on warm white / plum on after dark), like the kit's search field.
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            // Only the outline, label and message turn red on error; a "show password" eye stays a neutral control.
            errorTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        supportingText = if (!showSupporting) null else {
            {
                Row {
                    Text(error ?: supportingText.orEmpty(), modifier = Modifier.weight(1f))
                    if (maxLength != null) {
                        Text(
                            "${value.length}/$maxLength",
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Email input with the right keyboard and autofill hint; lowercases nothing (the server normalises). The IME action
 * moves to the next field by default; [onImeAction] runs first if you need more (e.g. submit on Done).
 */
@Composable
fun EmailField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Campus email",
    error: String? = null,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    AppTextField(
        value = value,
        onValueChange = { onValueChange(it.trim()) },
        label = label,
        modifier = modifier,
        error = error,
        supportingText = supportingText,
        placeholder = "you@school.edu",
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = {
            onImeAction()
            defaultKeyboardAction(imeAction)
        }),
        contentType = ContentType.EmailAddress + ContentType.Username,
    )
}

/**
 * Password input with an eye button to show / hide it and the autofill hint for a saved password, or for a new
 * one with [newPassword] (sign-up: password managers offer to generate and save it).
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Password",
    error: String? = null,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    newPassword: Boolean = false,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        error = error,
        supportingText = supportingText,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = {
            onImeAction()
            defaultKeyboardAction(imeAction)
        }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                // The icon shows the current state (eye open = visible), the description says what a tap does.
                Icon(
                    if (visible) QuadIcons.Visibility else QuadIcons.VisibilityOff,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        contentType = if (newPassword) ContentType.NewPassword else ContentType.Password,
    )
}

/**
 * Calls [action] when focus leaves this field (or anything inside it), not on the first composition. For
 * validating a field once the user moves on: `AppTextField(…, modifier = Modifier.onFocusLost(vm::checkEmail))`.
 */
@Composable
fun Modifier.onFocusLost(action: () -> Unit): Modifier {
    var hadFocus by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(action)
    return onFocusChanged { state ->
        if (hadFocus && !state.hasFocus) current()
        hadFocus = state.hasFocus
    }
}

/**
 * Dollar amount input. Keep the raw text in state and convert with `Format.parsePriceCents(text)`;
 * initialise from cents with `Format.priceInput(cents)`.
 */
@Composable
fun PriceField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Price",
    error: String? = null,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    AppTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = label,
        modifier = modifier,
        error = error,
        supportingText = supportingText,
        placeholder = "0.00",
        prefix = { Text("$") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
    )
}

/**
 * One-time code input: [length] boxes that fill as you type (digits only), with the number keyboard and the
 * one-time-code autofill hint. [onDone] runs from the keyboard's Done key. [error] outlines the boxes in red and
 * shows the message below.
 */
@Composable
fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    length: Int,
    modifier: Modifier = Modifier,
    label: String = "Verification code",
    error: String? = null,
    enabled: Boolean = true,
    onDone: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        BasicTextField(
            value = value,
            onValueChange = { text -> onValueChange(text.filter { it.isDigit() }.take(length)) },
            enabled = enabled,
            singleLine = true,
            interactionSource = interaction,
            // The boxes are the visible text; the real caret would sit after the last digit and only confuse.
            cursorBrush = SolidColor(scheme.primary.copy(alpha = 0f)),
            textStyle = TextStyle(color = scheme.onSurface.copy(alpha = 0f)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onDone()
                defaultKeyboardAction(ImeAction.Done)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    contentType = ContentType.SmsOtpCode
                    if (error != null) this.error(error)
                },
            decorationBox = { inner ->
                Box {
                    Box(Modifier.matchParentSize()) { inner() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally)) {
                        for (i in 0 until length) {
                            val digit = value.getOrNull(i)
                            val active = focused && (i == value.length || (i == length - 1 && value.length == length))
                            val border = when {
                                error != null -> scheme.error
                                active -> scheme.primary
                                else -> scheme.outline
                            }
                            Surface(
                                color = scheme.surfaceContainerLow,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(max = 56.dp)
                                    .aspectRatio(0.84f)
                                    .border(if (active || error != null) 2.dp else 1.dp, border, MaterialTheme.shapes.small),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        digit?.toString() ?: "",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = scheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
    }
}
