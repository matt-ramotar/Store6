package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileNamesTest {
    @Test
    fun requireComponentLengths_accepts159Utf8BytesOnEachPart() {
        val atLimit = "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES)
        FileNames.requireComponentLengths(namespace = atLimit, canonicalId = "id")
        FileNames.requireComponentLengths(namespace = "ns", canonicalId = atLimit)
        FileNames.requireComponentLengths(namespace = atLimit, canonicalId = atLimit)
        FileNames.requireComponentLengths(namespace = "", canonicalId = "")
    }

    @Test
    fun requireComponentLengths_rejects160Utf8BytesOnNamespace() {
        val overLimit = "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES + 1)
        val error =
            assertFailsWith<IllegalArgumentException> {
                FileNames.requireComponentLengths(namespace = overLimit, canonicalId = "id")
            }
        assertExceptionNamesPartLimitAndActual(
            message = error.message,
            part = "namespace",
            actualLength = overLimit.encodeToByteArray().size,
        )
    }

    @Test
    fun requireComponentLengths_rejects160Utf8BytesOnCanonicalId() {
        val overLimit = "a".repeat(FileNames.MAX_COMPONENT_UTF8_BYTES + 1)
        val error =
            assertFailsWith<IllegalArgumentException> {
                FileNames.requireComponentLengths(namespace = "ns", canonicalId = overLimit)
            }
        assertExceptionNamesPartLimitAndActual(
            message = error.message,
            part = "canonical id",
            actualLength = overLimit.encodeToByteArray().size,
        )
    }

    @Test
    fun requireComponentLengths_measuresUtf8BytesNotCharacters() {
        val twoByteChar = "é"
        assertEquals(2, twoByteChar.encodeToByteArray().size)
        val atLimit = twoByteChar.repeat(79) + "a"
        assertEquals(FileNames.MAX_COMPONENT_UTF8_BYTES, atLimit.encodeToByteArray().size)
        FileNames.requireComponentLengths(namespace = atLimit, canonicalId = atLimit)

        val overLimit = twoByteChar.repeat(80)
        assertEquals(160, overLimit.encodeToByteArray().size)
        val namespaceError =
            assertFailsWith<IllegalArgumentException> {
                FileNames.requireComponentLengths(namespace = overLimit, canonicalId = "id")
            }
        assertExceptionNamesPartLimitAndActual(
            message = namespaceError.message,
            part = "namespace",
            actualLength = 160,
        )
        val canonicalError =
            assertFailsWith<IllegalArgumentException> {
                FileNames.requireComponentLengths(namespace = "ns", canonicalId = overLimit)
            }
        assertExceptionNamesPartLimitAndActual(
            message = canonicalError.message,
            part = "canonical id",
            actualLength = 160,
        )
    }

    @Test
    fun keyPath_encodesUnderCallerSubtree() {
        val root = Path("values")
        assertEquals(
            Path(root, Base32.encode("orders"), Base32.encode("42")),
            FileNames.keyPath(root, namespace = "orders", canonicalId = "42"),
        )
        assertEquals(
            Path(root, "0", "0"),
            FileNames.keyPath(root, namespace = "", canonicalId = ""),
        )
    }

    @Test
    fun namespaceDirectory_encodesUnderCallerSubtree() {
        val root = Path("records")
        assertEquals(
            Path(root, Base32.encode("orders")),
            FileNames.namespaceDirectory(root, namespace = "orders"),
        )
        assertEquals(Path(root, "0"), FileNames.namespaceDirectory(root, namespace = ""))
    }

    @Test
    fun corruptSibling_appendsCorruptSuffixToFileName() {
        val path = Path("values", "aaa", "bbb")
        assertEquals(Path("values", "aaa", "bbb.corrupt"), FileNames.corruptSibling(path))
        assertEquals(Path("bbb.corrupt"), FileNames.corruptSibling(Path("bbb")))
    }

    private fun assertExceptionNamesPartLimitAndActual(
        message: String?,
        part: String,
        actualLength: Int,
    ) {
        val text = requireNotNull(message)
        assertTrue(text.contains(part), "message must name $part: $text")
        assertTrue(
            text.contains(FileNames.MAX_COMPONENT_UTF8_BYTES.toString()),
            "message must name limit ${FileNames.MAX_COMPONENT_UTF8_BYTES}: $text",
        )
        assertTrue(
            text.contains(actualLength.toString()),
            "message must name actual length $actualLength: $text",
        )
    }
}
