package io.github.hsd221.phonebridge

import android.content.Context

/** Resolves user-facing messages through Android resources, including service errors. */
internal object I18n {
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    fun get(id: Int, vararg args: Any): String =
        appContext?.getString(id, *args) ?: id.toString()
}

internal fun text(id: Int, vararg args: Any): String = I18n.get(id, *args)
