package com.pocketds.hub.ui

import android.content.Intent
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ActivityItem
import com.pocketds.hub.model.ArrRef
import com.pocketds.hub.model.Stages
import com.pocketds.hub.screens.downloads.DownloadRowView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferRowTest {
    @Test fun progressAndLongFailureUpdateInPlaceWithoutLosingFocus() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { i.runOnMainSync {
            val row=DownloadRowView(activity,Theme.colors(activity))
            FocusDecorator.attach(row,{true},scale=false)
            activity.setContentView(FrameLayout(activity).apply{addView(row,FrameLayout.LayoutParams(-1,-2))})
            val item=ActivityItem(id="fixture",title="A long series transfer",stage=Stages.DOWNLOADING,sizeBytes=8_000_000_000)
            row.bind(item);assertTrue(row.requestFocus())
            repeat(20){index->row.bind(item.copy(progress=index/20.0,speedBps=1_250_000));assertTrue(row.hasFocus());assertEquals(1f,row.scaleX)}
            val message="No writable space is available in the destination folder. ".repeat(8)
            row.bind(item.copy(stage=Stages.STUCK,arr=ArrRef(problem=message)))
            assertTrue(row.hasFocus());assertTrue(row.contentDescription.contains(message))
        }} finally {i.runOnMainSync{activity.finish()}}
    }
}
