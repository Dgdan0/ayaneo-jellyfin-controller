package com.pocketds.hub.reader

import android.os.Parcel
import android.os.Parcelable
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * The style of "Heard to here"'s tab (#62): Readium picks a decoration's template by its style class, and this one's boxes are as wide as
 * a page (so that the tab can stand in the margin at the page's side, which no box wrapped round the words can reach), where the
 * underline's boxes wrap the words. [tint] is the colour the tab is drawn in. Parcelable by hand: the build has no parcelize.
 */
@OptIn(ExperimentalReadiumApi::class)
class MarginTab(val tint: Int) : Decoration.Style {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
    }

    companion object {
        @JvmField val CREATOR: Parcelable.Creator<MarginTab> = object : Parcelable.Creator<MarginTab> {
            override fun createFromParcel(source: Parcel): MarginTab = MarginTab(source.readInt())
            override fun newArray(size: Int): Array<MarginTab?> = arrayOfNulls(size)
        }
    }
}
