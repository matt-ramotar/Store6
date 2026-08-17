package org.mobilenativefoundation.store6.file.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Base32Test {
    @Test
    fun rfc4648_lowercaseUnpaddedVectors() {
        assertEquals("0", Base32.encode(""))
        assertEquals("my", Base32.encode("f"))
        assertEquals("mzxq", Base32.encode("fo"))
        assertEquals("mzxw6", Base32.encode("foo"))
        assertEquals("mzxw6yq", Base32.encode("foob"))
        assertEquals("mzxw6ytb", Base32.encode("fooba"))
        assertEquals("mzxw6ytboi", Base32.encode("foobar"))
    }

    @Test
    fun emptyString_encodesToSentinelZero() {
        assertEquals("0", Base32.encode(""))
    }

    @Test
    fun nonEmptyInputs_neverEncodeToSentinelZero() {
        for (b0 in 0..255) {
            val one = byteArrayOf(b0.toByte()).decodeToString()
            assertNotEquals("0", Base32.encode(one), "1-byte input $b0")
        }
        for (b0 in 0..255) {
            for (b1 in 0..255) {
                val two = byteArrayOf(b0.toByte(), b1.toByte()).decodeToString()
                assertNotEquals("0", Base32.encode(two), "2-byte input $b0,$b1")
            }
        }
        for (b0 in 0..255) {
            for (b1 in 0..255) {
                for (b2 in 0..255 step 17) {
                    val three =
                        byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte()).decodeToString()
                    assertNotEquals("0", Base32.encode(three), "3-byte input $b0,$b1,$b2")
                }
            }
        }
    }
}
