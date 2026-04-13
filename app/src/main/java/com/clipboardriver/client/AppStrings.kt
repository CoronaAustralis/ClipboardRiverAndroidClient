package com.clipboardriver.client

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

class AppStrings(
    private val context: Context,
) {
    fun get(@StringRes resId: Int, vararg args: Any): String = context.getString(resId, *args)

    fun quantity(@PluralsRes resId: Int, quantity: Int, vararg args: Any): String =
        context.resources.getQuantityString(resId, quantity, *args)
}
