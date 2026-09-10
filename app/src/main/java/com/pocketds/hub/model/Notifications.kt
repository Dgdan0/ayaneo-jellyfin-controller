package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable
data class ServiceNotice(
    val id: String = "",
    val service: String = "",
    val kind: String = "",
    val severity: String = "info",
    val title: String = "",
    val detail: String = "",
    val occurredAt: String = "",
    val timeLabel: String = "",
    /** Current health problems stay pinned above historical events. */
    val active: Boolean = false
)

@Serializable
data class NotificationSection(
    val service: String = "",
    val state: String = "disabled",
    val items: List<ServiceNotice> = emptyList()
)

@Serializable
data class NotificationsResponse(
    val generatedAt: String = "",
    val attentionCount: Int = 0,
    val sections: List<NotificationSection> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)
