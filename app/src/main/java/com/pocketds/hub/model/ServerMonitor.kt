package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable data class ServerMonitor(
    val host: HostSnapshot = HostSnapshot(), val containers: List<HostContainer> = emptyList(),
    val sessions: List<HostSession> = emptyList(), val dockerWarning: String = "",
    val sessionWarning: String = "", val checkedAt: String = ""
)
@Serializable data class HostSnapshot(
    val os: String = "", val cpuPercent: Double? = null, val memoryTotalBytes: Long = 0,
    val memoryAvailableBytes: Long = 0, val uptimeSeconds: Long = 0,
    val disks: List<HostDisk> = emptyList(), val warnings: List<String> = emptyList()
)
@Serializable data class HostDisk(val name: String, val totalBytes: Long = 0, val availableBytes: Long = 0)
@Serializable data class HostContainer(val name: String, val image: String = "", val state: String = "", val status: String = "")
@Serializable data class HostSession(val title: String, val device: String = "", val client: String = "", val method: String = "", val paused: Boolean = false)
