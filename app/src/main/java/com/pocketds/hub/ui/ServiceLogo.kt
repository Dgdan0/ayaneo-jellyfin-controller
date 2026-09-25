package com.pocketds.hub.ui

import android.content.res.Configuration
import android.widget.ImageView

/** Reload a real logo's light/night asset without restarting the Activity or tinting the logo. */
object ServiceLogo {
    private const val RESOURCE_TAG = -0x7fffffc2

    fun bind(view: ImageView, resource: Int) {
        view.setTag(RESOURCE_TAG, resource)
        refresh(view)
    }

    fun refresh(view: ImageView) {
        val resource = view.getTag(RESOURCE_TAG) as? Int ?: return
        val configuration = Configuration(view.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (Theme.isDark(view.context)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = view.context.createConfigurationContext(configuration)
        view.setImageDrawable(themed.getDrawable(resource))
    }
}
