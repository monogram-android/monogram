package org.monogram.data.mapper

import java.util.concurrent.atomic.AtomicReference

private val sponsorIdsRef = AtomicReference<Set<Long>>(emptySet())

fun updateSponsorIds(ids: Set<Long>) {
    sponsorIdsRef.set(ids)
}

fun isSponsoredUser(userId: Long): Boolean {
    return userId in sponsorIdsRef.get()
}
