package org.mobilenativefoundation.store6.file.internal

/**
 * Lowercase unpadded RFC 4648 base32 used for on-disk name components.
 *
 * The alphabet is `a–z` and `2–7`. Padding characters are not emitted.
 */
internal object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    /**
     * Encodes the UTF-8 bytes of [value].
     *
     * The empty string encodes to `"0"`. `0` is outside the alphabet, so that sentinel cannot
     * collide with the encoding of any non-empty input. Reversibility is not load-bearing: no
     * adapter path decodes names.
     */
    fun encode(value: String): String {
        if (value.isEmpty()) return "0"
        return encodeBytes(value.encodeToByteArray())
    }

    private fun encodeBytes(bytes: ByteArray): String {
        val output = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val index = (buffer ushr bitsLeft) and 0x1F
                output.append(ALPHABET[index])
            }
            buffer = buffer and ((1 shl bitsLeft) - 1)
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            output.append(ALPHABET[index])
        }
        return output.toString()
    }
}
