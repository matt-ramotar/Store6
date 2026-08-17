package org.mobilenativefoundation.store6.file.internal

import kotlinx.io.files.Path

/**
 * On-disk name mapping for one `(namespace, canonicalId)` pair.
 *
 * Encoded components are lowercase unpadded RFC 4648 base32 of the UTF-8 bytes, with the
 * empty-string sentinel `"0"` from [Base32.encode]. Callers supply the subtree root
 * (`values/` or `records/`). This object composes child [Path] values and does not touch
 * the filesystem.
 */
internal object FileNames {
    /**
     * Maximum UTF-8 byte length of `namespace.value` and of `canonicalId()`, each.
     *
     * Base32 expands by 8/5, and `ceil(159 × 8 / 5) = 255`, which is the name-component
     * budget on ext4, APFS, and NTFS for these ASCII encodings.
     */
    const val MAX_COMPONENT_UTF8_BYTES: Int = 159

    /** Suffix appended to a file name to form its quarantine sibling. */
    const val CORRUPT_SUFFIX: String = ".corrupt"

    /**
     * Throws [IllegalArgumentException] when the UTF-8 byte length of [namespace] or
     * [canonicalId] exceeds [MAX_COMPONENT_UTF8_BYTES].
     *
     * The exception message names the offending part (`namespace` or `canonical id`), the
     * limit (`159`), and the actual UTF-8 byte length. [namespace] is checked first.
     * Empty strings are 0 bytes and are accepted. Their encoded names use the `"0"` sentinel.
     */
    fun requireComponentLengths(
        namespace: String,
        canonicalId: String,
    ) {
        val namespaceByteLength = namespace.encodeToByteArray().size
        require(namespaceByteLength <= MAX_COMPONENT_UTF8_BYTES) {
            "namespace UTF-8 byte length $namespaceByteLength exceeds limit $MAX_COMPONENT_UTF8_BYTES"
        }
        val canonicalIdByteLength = canonicalId.encodeToByteArray().size
        require(canonicalIdByteLength <= MAX_COMPONENT_UTF8_BYTES) {
            "canonical id UTF-8 byte length $canonicalIdByteLength exceeds limit $MAX_COMPONENT_UTF8_BYTES"
        }
    }

    /**
     * Directory of one namespace under [subtreeRoot]: `<subtreeRoot>/<enc(namespace)>`.
     *
     * Does not enforce [MAX_COMPONENT_UTF8_BYTES]. Call [requireComponentLengths] first when
     * the strings come from a key.
     */
    fun namespaceDirectory(
        subtreeRoot: Path,
        namespace: String,
    ): Path = Path(subtreeRoot, Base32.encode(namespace))

    /**
     * File of one key under [subtreeRoot]:
     * `<subtreeRoot>/<enc(namespace)>/<enc(canonicalId)>`.
     *
     * Does not enforce [MAX_COMPONENT_UTF8_BYTES]. Call [requireComponentLengths] first when
     * the strings come from a key.
     */
    fun keyPath(
        subtreeRoot: Path,
        namespace: String,
        canonicalId: String,
    ): Path = Path(subtreeRoot, Base32.encode(namespace), Base32.encode(canonicalId))

    /**
     * Quarantine sibling of [path]: the same parent, file name plus [CORRUPT_SUFFIX].
     *
     * `.` is outside the base32 alphabet, so this name cannot collide with a value or
     * record name. When [path] has no parent, the result is a relative path of the
     * suffixed file name alone.
     */
    fun corruptSibling(path: Path): Path {
        val name = path.name + CORRUPT_SUFFIX
        val parent = path.parent
        return if (parent != null) {
            Path(parent, name)
        } else {
            Path(name)
        }
    }
}
