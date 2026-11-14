package com.clipboardriver.client

import android.content.Context
import androidx.annotation.StringRes

fun interface StringProvider {
    fun get(@StringRes resId: Int, vararg args: Any): String
}

class AppStrings(private val context: Context) : StringProvider {
    override fun get(@StringRes resId: Int, vararg args: Any): String = context.getString(resId, *args)
}