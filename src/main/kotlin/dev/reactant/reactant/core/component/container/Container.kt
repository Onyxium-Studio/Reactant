package dev.reactant.reactant.core.component.container

import io.github.classgraph.ScanResult
import kotlin.reflect.KClass

/**
 * The container which holding the Component classes
 */
interface Container {
    val componentClasses: Set<KClass<out Any>>
    val displayName: String
    val identifier: String
    val scanResult: ScanResult

    fun getClassesByAnnotation(annotation: KClass<out Annotation>): Set<KClass<out Any>>
}
