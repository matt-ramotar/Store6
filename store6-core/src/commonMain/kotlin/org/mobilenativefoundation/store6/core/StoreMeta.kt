package org.mobilenativefoundation.store6.core

/**
 * Typed freshness and identity metadata attached to stored values.
 *
 * At this stage the interface is a marker: it exists so that [StoreError.Conflict] can carry a
 * typed server-side metadata payload from day one instead of an untyped placeholder. The full
 * shape (written-at instant, optional entity tag) is introduced with the freshness engine and is
 * an open review item — no timestamp representation is committed here because kotlin.time's
 * Instant is not stable on the current language floor.
 */
public interface StoreMeta
