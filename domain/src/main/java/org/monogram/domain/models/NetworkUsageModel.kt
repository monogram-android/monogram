package org.monogram.domain.models

data class NetworkUsageModel(
    val mobile: NetworkTypeUsage,
    val wifi: NetworkTypeUsage,
    val roaming: NetworkTypeUsage,
    val other: NetworkTypeUsage
)

data class NetworkTypeUsage(
    val sent: Long,
    val received: Long,
    val details: List<NetworkUsageCategory>
)

data class NetworkUsageCategory(
    val name: String,
    val sent: Long,
    val received: Long
)
