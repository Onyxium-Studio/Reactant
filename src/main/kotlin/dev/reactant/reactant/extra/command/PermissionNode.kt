package dev.reactant.reactant.extra.command

import java.util.*

interface PermissionNode

open class PermissionRoot(protected val prefix: String) : PermissionNode {

    open class S(_prefix: String) : PermissionNode {
        protected val prefix = "${_prefix}.${this::class.simpleName?.lowercase(Locale.getDefault())!!}"
        override fun toString() = prefix
    }

    override fun toString(): String = prefix
}


