package org.monogram.presentation.features.chats.conversation.ui.content

internal data class EdgeLoadDecision(
    val shouldLoadOlder: Boolean,
    val shouldLoadNewer: Boolean,
    val nextOlderAnchorId: Long?,
    val nextNewerAnchorId: Long?
)

internal fun decideEdgeLoad(
    isComments: Boolean,
    isOldestLoaded: Boolean,
    isLatestLoaded: Boolean,
    isAtBottom: Boolean,
    nearOlderEdge: Boolean,
    nearNewerEdge: Boolean,
    olderAnchorId: Long?,
    newerAnchorId: Long?,
    lastOlderAnchorId: Long?,
    lastNewerAnchorId: Long?
): EdgeLoadDecision {
    val shouldLoadOlder = nearOlderEdge &&
            !isOldestLoaded &&
            olderAnchorId != null &&
            olderAnchorId > 0L &&
            olderAnchorId != lastOlderAnchorId

    val shouldLoadNewer = nearNewerEdge &&
            !isLatestLoaded &&
            (!isAtBottom || isComments) &&
            newerAnchorId != null &&
            newerAnchorId > 0L &&
            newerAnchorId != lastNewerAnchorId

    return EdgeLoadDecision(
        shouldLoadOlder = shouldLoadOlder,
        shouldLoadNewer = shouldLoadNewer,
        nextOlderAnchorId = olderAnchorId?.takeIf { shouldLoadOlder },
        nextNewerAnchorId = newerAnchorId?.takeIf { shouldLoadNewer }
    )
}
