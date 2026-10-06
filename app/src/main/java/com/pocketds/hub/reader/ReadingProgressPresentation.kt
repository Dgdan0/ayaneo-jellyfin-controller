package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingContinue
import com.pocketds.hub.model.ReadingProgress as ProgressView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Project unacknowledged local progress over a possibly stale catalog response. */
object ReadingProgressPresentation {
    fun project(work:ReadingWork,pending:List<ReadingCheckpoint>):ReadingWork {
        val items=work.sections.flatMap { it.items }
        val ids=items.map { it.sourceItemId }.toSet()+work.editions.map { it.sourceItemId }+listOfNotNull(work.continueAt?.sourceItemId)
        // Null for a place that says nothing of how far through the book it is.
        fun view(checkpoint:ReadingCheckpoint):ProgressView? {
            val local=requireNotNull(checkpoint.local)
            val pages=items.firstOrNull { it.sourceItemId==checkpoint.key.sourceItemId }?.pageCount
                ?: work.editions.firstOrNull { it.sourceItemId==checkpoint.key.sourceItemId }?.pageCount ?: 0
            val locations=local.locator?.get("locations") as? JsonObject
            val fraction=when(checkpoint.key.kind) {
                // A listening place is a track and a moment, with how far through the book it is kept beside
                // them (#30). One kept before that says nothing, and 0% would be a claim: it is left out, so
                // the hub's own progress stands until the place is sent.
                AudioPlace.KIND -> AudioPlace.progressOf(local) ?: return null
                else -> (locations?.get("totalProgression") as? JsonPrimitive)?.doubleOrNull
                    ?: if(pages>0 && local.pageIndex!=null)(local.pageIndex+1).toDouble()/pages else 0.0
            }
            return ProgressView(percentage=fraction.coerceIn(0.0,1.0),completed=fraction>=1.0,
                current=local.pageIndex?.plus(1) ?: 0,total=pages)
        }
        val relevant=pending.filter { it.pending && !it.conflicted && it.key.sourceItemId in ids && it.local!=null }
            .mapNotNull { checkpoint -> view(checkpoint)?.let { checkpoint to it } }
        if(relevant.isEmpty())return work
        val (latest,position)=relevant.maxBy { it.first.updatedAt }
        val item=items.firstOrNull { it.sourceItemId==latest.key.sourceItemId }
        val edition=work.editions.firstOrNull { it.sourceItemId==latest.key.sourceItemId }
        return work.copy(progress=if(work.entityType=="collection")work.progress else position,
            continueAt=ReadingContinue(workId=latest.key.workId,source=edition?.source ?: if(latest.key.kind=="epub")"storyteller" else "kavita",
                sourceItemId=latest.key.sourceItemId,title=item?.title ?: work.title,number=item?.number.orEmpty(),
                percentage=position.percentage,artwork=item?.artwork ?: work.artwork,kind=item?.kind ?: work.kind),
            sections=work.sections.map { section -> section.copy(items=section.items.map { entry ->
                relevant.lastOrNull { it.first.key.sourceItemId==entry.sourceItemId }?.let { entry.copy(progress=it.second) } ?: entry
            }) })
    }
}
