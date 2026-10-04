package com.pocketds.hub.model

import kotlinx.serialization.Serializable

/** One artwork's Glass colours from `GET /v1/img/colors`, each "#rrggbb" (GLASS_PLAN.md, #10). */
@Serializable
data class ArtworkColorSet(
    val dominant: String = "",
    val dark: String = "",
    val vivid: String = "",
    val light: String = ""
)

/**
 * Keys echo each `src` exactly as sent. [pending] is still being worked out on
 * the hub: ask again shortly. [missing] cannot be read: do not ask again.
 */
@Serializable
data class ArtworkColorsResponse(
    val colors: Map<String, ArtworkColorSet> = emptyMap(),
    val pending: List<String> = emptyList(),
    val missing: List<String> = emptyList()
)
