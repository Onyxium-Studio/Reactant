package io.github.clayclaw.lightcargo.kts.definition.annotation

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class Import(vararg val scriptPaths: String)

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class PriorityMarker(val priority: ScriptPriority)

enum class ScriptPriority(val value: Int) {
    HIGHEST(0), HIGH(1), NORMAL(2), LOW(3), LOWEST(4)
}