package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable
data class BandwidthState(
    val mode: String = "normal",
    val downloadBps: Long = 0,
    val uploadBps: Long = 0,
    val alternativeDownloadBps: Long = 0,
    val alternativeUploadBps: Long = 0,
    val queueingEnabled: Boolean = false,
    val schedulerEnabled: Boolean = false,
    val modeSwitchSupported: Boolean = false,
    val canControl: Boolean = false
)

@Serializable
data class BandwidthChange(
    val mode: String = "",
    val limitsFor: String = "",
    val downloadBps: Long? = null,
    val uploadBps: Long? = null
)
