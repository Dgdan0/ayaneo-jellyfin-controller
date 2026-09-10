package com.pocketds.hub.offline

enum class TransferWriteMode { APPEND, RESTART, COMPLETE }

/** Pure HTTP resume decision; invalid combinations fail instead of corrupting a partial file. */
fun transferWriteMode(existing: Long, expected: Long, statusCode: Int): TransferWriteMode {
    require(existing >= 0 && expected > 0 && existing <= expected) { "invalid stored byte count" }
    return when {
        existing == expected && (statusCode == 206 || statusCode == 416) -> TransferWriteMode.COMPLETE
        existing > 0 && statusCode == 206 -> TransferWriteMode.APPEND
        statusCode in 200..299 -> TransferWriteMode.RESTART
        else -> error("HTTP $statusCode cannot continue a download")
    }
}

fun validContentRange(existing: Long, expected: Long, statusCode: Int, value: String?): Boolean {
    if (statusCode != 206) return existing == 0L || statusCode == 200 || statusCode == 416
    val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
        .matchEntire(value?.trim().orEmpty()) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
    return start == existing && end >= start && (total == null || total == expected)
}
