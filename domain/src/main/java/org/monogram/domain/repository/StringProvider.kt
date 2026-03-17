package org.monogram.domain.repository

interface StringProvider {
    fun getString(resName: String): String
    fun getString(resName: String, vararg formatArgs: Any): String
    fun getQuantityString(resName: String, quantity: Int, vararg formatArgs: Any): String
}
