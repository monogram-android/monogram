package org.monogram.network.http

/** Telegram photo/document fetch quality. Higher quality is a larger getFile size. */
enum class MediaFetchKind {
    /** Small preview (`m`/`s`). */
    Thumb,
    /** Chat-bubble JPEG (`x`/`y`) when the original is larger. */
    Display,
    /** Original / full document. */
    Full,
}
