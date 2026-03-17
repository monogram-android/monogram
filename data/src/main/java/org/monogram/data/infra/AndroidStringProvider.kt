package org.monogram.data.infra

import android.content.Context
import org.monogram.domain.repository.StringProvider

class AndroidStringProvider(private val context: Context) : StringProvider {
    override fun getString(resName: String): String {
        val resId = context.resources.getIdentifier(resName, "string", context.packageName)
        return if (resId != 0) context.getString(resId) else resName
    }

    override fun getString(resName: String, vararg formatArgs: Any): String {
        val resId = context.resources.getIdentifier(resName, "string", context.packageName)
        return if (resId != 0) context.getString(resId, *formatArgs) else resName
    }

    override fun getQuantityString(resName: String, quantity: Int, vararg formatArgs: Any): String {
        val resId = context.resources.getIdentifier(resName, "plurals", context.packageName)
        return if (resId != 0) context.resources.getQuantityString(resId, quantity, *formatArgs) else resName
    }
}
