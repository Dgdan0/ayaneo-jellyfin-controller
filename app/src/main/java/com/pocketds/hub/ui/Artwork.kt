package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import coil.ImageLoader
import coil.dispose
import coil.request.ImageRequest
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient

/**
 * The one way a hub image reaches a view.
 *
 * Every screen used to spell out `(api as? HubClient)?.imageLoader ?:
 * ImageLoader(context)` itself -- fifty times -- and against the fake API that
 * built a new loader, with its own memory cache, per card. Several also
 * returned early for a blank path, which left a recycled card's previous
 * request running, so it could finish into the wrong title.
 */
object Artwork {

    @Volatile private var fallback: ImageLoader? = null

    /** The hub client's loader: one memory cache and one connection pool for every image. */
    fun loader(api: HubApi, context: Context): ImageLoader =
        (api as? HubClient)?.imageLoader ?: fallback ?: synchronized(this) {
            fallback ?: ImageLoader(context.applicationContext).also { fallback = it }
        }

    /**
     * Loads [data] -- an absolute URL, a local file or uri -- into [view]. A
     * null or blank value cancels the view's previous request and clears it,
     * and [onMissing] runs when there is no artwork or it fails to load, so a
     * card can say what it is instead of staying an empty box.
     */
    fun bind(
        view: ImageView,
        loader: ImageLoader,
        data: Any?,
        onMissing: (() -> Unit)? = null,
        /** Posters and stills: half the memory. Never for logos, which need alpha. */
        opaque: Boolean = false,
        /** Shown while loading and when there is nothing to load. */
        placeholderColor: Int? = null,
        configure: ImageRequest.Builder.() -> Unit = {}
    ) {
        val placeholder = placeholderColor?.let(::ColorDrawable)
        val value = data?.takeUnless { it is String && it.isBlank() }
        if (value == null) {
            view.dispose()
            view.setImageDrawable(placeholder)
            onMissing?.invoke()
            return
        }
        loader.enqueue(
            ImageRequest.Builder(view.context)
                .data(value)
                .target(view)
                .apply { if (placeholder != null) placeholder(placeholder).error(placeholder) }
                .apply { if (opaque) bitmapConfig(Bitmap.Config.RGB_565) }
                .apply { if (onMissing != null) listener(onError = { _, _ -> onMissing() }) }
                .apply(configure)
                .build()
        )
    }

    /** [bind] for a hub-relative path such as `/v1/img/jf/…`. */
    fun bindHub(
        view: ImageView,
        api: HubApi,
        hubPath: String,
        onMissing: (() -> Unit)? = null,
        opaque: Boolean = false,
        placeholderColor: Int? = null,
        configure: ImageRequest.Builder.() -> Unit = {}
    ) = bind(view, loader(api, view.context), api.imageUrl(hubPath), onMissing, opaque, placeholderColor, configure)
}
