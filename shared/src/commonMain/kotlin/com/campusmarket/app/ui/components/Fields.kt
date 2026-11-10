package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign

/**
 * The app's text field: outlined, full width, with a field error (red, replaces the supporting text) and an
 * optional character counter when [maxLength] is set (input is truncated to it).
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
) {
    val showSupporting = error != null || supportingText != null || maxLength != null
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(if (maxLength != null) it.take(maxLength) else it) },
        modifier = modifier.fillMaxWidth(),
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

/** Email input with the right keyboard; lowercases nothing (the server normalises). */
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
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
    )
}

/** Password input with a Show/Hide toggle. */
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
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") }
        },
    )
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
