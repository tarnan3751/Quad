package com.campusmarket.app.feature.listing

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.campusmarket.app.ui.components.AppTextField

/**
 * Dollar amount with a decimal keypad, "$" prefix and thousands separators while typing ("1250" shows "1,250"). The
 * state keeps plain digits (`Format.parsePriceCents` reads it); input is limited to digits, one point and two decimals.
 */
@Composable
internal fun PriceInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Price",
    error: String? = null,
    supportingText: String? = null,
    placeholder: String? = "0",
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    AppTextField(
        value = value,
        onValueChange = { onValueChange(cleanPriceInput(it)) },
        label = label,
        modifier = modifier,
        error = error,
        supportingText = supportingText,
        placeholder = placeholder,
        prefix = { Text("$") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = {
            onImeAction()
            defaultKeyboardAction(imeAction)
        }),
        visualTransformation = PriceGrouping,
    )
}

private const val MAX_WHOLE_DIGITS = 6

/** Digits and at most one decimal point with up to two decimals; separators and anything else are dropped. */
internal fun cleanPriceInput(text: String): String {
    val sb = StringBuilder()
    var seenPoint = false
    var decimals = 0
    var whole = 0
    for (c in text) {
        when {
            c.isDigit() && !seenPoint && whole < MAX_WHOLE_DIGITS -> { sb.append(c); whole++ }
            c.isDigit() && seenPoint && decimals < 2 -> { sb.append(c); decimals++ }
            c == '.' && !seenPoint -> { sb.append(c); seenPoint = true }
        }
    }
    return sb.toString()
}

/** Shows "1250.5" as "1,250.5"; the cursor maps across the inserted commas. */
internal object PriceGrouping : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val point = raw.indexOf('.').let { if (it < 0) raw.length else it }
        val out = StringBuilder()
        // originalToTransformed for every original offset 0..raw.length
        val toOut = IntArray(raw.length + 1)
        for (i in raw.indices) {
            if (i in 1 until point && (point - i) % 3 == 0) out.append(',')
            toOut[i] = out.length
            out.append(raw[i])
        }
        toOut[raw.length] = out.length
        val toRaw = IntArray(out.length + 1)
        var r = 0
        for (t in 0..out.length) {
            while (r < raw.length && toOut[r] < t) r++
            toRaw[t] = r
        }
        return TransformedText(
            AnnotatedString(out.toString()),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = toOut[offset.coerceIn(0, raw.length)]
                override fun transformedToOriginal(offset: Int): Int = toRaw[offset.coerceIn(0, out.length)]
            },
        )
    }
}
