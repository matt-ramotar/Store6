package org.mobilenativefoundation.store6.ktor

// Present only so each Kotlin/Native target produces a klib while the module has no real
// sources yet: the binary-compatibility klib dump cannot infer Apple-target ABI on a Linux
// host from an empty module. Removed when the first real source lands.
internal object KtorModulePlaceholder
