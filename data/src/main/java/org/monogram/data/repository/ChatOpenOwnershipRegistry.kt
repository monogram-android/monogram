package org.monogram.data.repository

internal enum class ChatOpenOwnershipTransition {
    AcquiredFirstOwner,
    AddedOwner,
    DuplicateOwner,
    ReleasedOwner,
    ReleasedLastOwner,
    MissingOwner
}

internal data class ChatOpenOwnershipChange(
    val transition: ChatOpenOwnershipTransition,
    val ownerCount: Int,
    val owners: Set<String>
) {
    val shouldOpen: Boolean
        get() = transition == ChatOpenOwnershipTransition.AcquiredFirstOwner

    val shouldClose: Boolean
        get() = transition == ChatOpenOwnershipTransition.ReleasedLastOwner
}

internal class ChatOpenOwnershipRegistry {
    private val ownersByChatId = mutableMapOf<Long, LinkedHashSet<String>>()

    fun acquire(chatId: Long, ownerTag: String): ChatOpenOwnershipChange {
        val owners = ownersByChatId.getOrPut(chatId) { linkedSetOf() }
        val added = owners.add(ownerTag)
        val transition = when {
            !added -> ChatOpenOwnershipTransition.DuplicateOwner
            owners.size == 1 -> ChatOpenOwnershipTransition.AcquiredFirstOwner
            else -> ChatOpenOwnershipTransition.AddedOwner
        }
        return ChatOpenOwnershipChange(
            transition = transition,
            ownerCount = owners.size,
            owners = owners.toSet()
        )
    }

    fun release(chatId: Long, ownerTag: String): ChatOpenOwnershipChange {
        val owners = ownersByChatId[chatId]
            ?: return ChatOpenOwnershipChange(
                transition = ChatOpenOwnershipTransition.MissingOwner,
                ownerCount = 0,
                owners = emptySet()
            )

        val removed = owners.remove(ownerTag)
        if (!removed) {
            return ChatOpenOwnershipChange(
                transition = ChatOpenOwnershipTransition.MissingOwner,
                ownerCount = owners.size,
                owners = owners.toSet()
            )
        }

        if (owners.isEmpty()) {
            ownersByChatId.remove(chatId)
            return ChatOpenOwnershipChange(
                transition = ChatOpenOwnershipTransition.ReleasedLastOwner,
                ownerCount = 0,
                owners = emptySet()
            )
        }

        return ChatOpenOwnershipChange(
            transition = ChatOpenOwnershipTransition.ReleasedOwner,
            ownerCount = owners.size,
            owners = owners.toSet()
        )
    }

    fun hasOwners(chatId: Long): Boolean = ownersByChatId[chatId]?.isNotEmpty() == true
}
