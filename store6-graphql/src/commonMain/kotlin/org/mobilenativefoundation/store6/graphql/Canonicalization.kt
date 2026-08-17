@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

// Canonical rendering rules (pinned by GraphQlCanonicalizationTest):
// - JSON-shaped, no whitespace.
// - Object keys sorted by UTF-16 code-unit order; list order preserved.
// - Strings JSON-escaped: `"` and `\` prefixed, short escapes \b \f \n \r \t, remaining
//   control characters below U+0020 as lowercase \u00xx.
// - Explicit NullValue renders `null`; an absent variable renders nothing, so the two are
//   distinct identities.
// - IntValue renders as a decimal integer. FloatValue renders as `f` followed by normalized
//   IEEE-754 bits in fixed-width lowercase hexadecimal, avoiding runtime number formatting.

internal fun GraphQlVariables.canonicalString(): String =
    buildString { appendCanonicalObject(entries) }

private fun StringBuilder.appendCanonical(value: GraphQlValue) {
    when (value) {
        is GraphQlValue.NullValue -> append("null")
        is GraphQlValue.BooleanValue -> append(value.value)
        is GraphQlValue.IntValue -> append(value.value)
        is GraphQlValue.FloatValue -> appendCanonicalFloat(value.value)
        is GraphQlValue.StringValue -> appendJsonEscaped(value.value)
        is GraphQlValue.ListValue -> {
            append('[')
            value.values.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                appendCanonical(entry)
            }
            append(']')
        }
        is GraphQlValue.ObjectValue -> appendCanonicalObject(value.fields)
    }
}

private fun StringBuilder.appendCanonicalObject(fields: Map<String, GraphQlValue>) {
    append('{')
    fields.keys.sorted().forEachIndexed { index, key ->
        if (index > 0) append(',')
        appendJsonEscaped(key)
        append(':')
        appendCanonical(fields.getValue(key))
    }
    append('}')
}

private fun StringBuilder.appendJsonEscaped(value: String) {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
        }
    }
    append('"')
}

private fun StringBuilder.appendCanonicalFloat(value: Double) {
    append('f')
    val normalized = if (value == 0.0) 0.0 else value
    val bits = normalized.toBits()
    for (shift in 60 downTo 0 step 4) {
        append(HEX_DIGITS[((bits ushr shift) and 0x0f).toInt()])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
