package com.pocketds.hub.reader

enum class CheckpointSyncResult { SYNCED, RETRY, CONFLICT }

/** Caller serializes synchronization; local saves may continue while a request is in flight. */
class ReadingCheckpointSync(
    private val store: ReadingCheckpointStore,
    private val fetch: suspend (ReadingCheckpointKey) -> RemoteReadingPosition,
    private val send: suspend (ReadingCheckpoint) -> Boolean
) {
    suspend fun sync(key: ReadingCheckpointKey): CheckpointSyncResult {
        val before=store.read(key) ?: return CheckpointSyncResult.SYNCED
        if (!before.pending) return CheckpointSyncResult.SYNCED
        if (before.conflicted) return CheckpointSyncResult.CONFLICT
        val remote=fetch(key)
        if (remote is RemoteReadingPosition.Unavailable) return CheckpointSyncResult.RETRY
        val resume=store.reconcile(key,remote)
        if (resume.conflict) return CheckpointSyncResult.CONFLICT
        val sent=store.read(key) ?: return CheckpointSyncResult.SYNCED
        if (!sent.pending) return CheckpointSyncResult.SYNCED
        if (!send(sent)) return CheckpointSyncResult.RETRY
        store.acknowledge(key,sent.revision,sent.local)
        return if (store.read(key)?.pending == true) CheckpointSyncResult.RETRY else CheckpointSyncResult.SYNCED
    }
}
