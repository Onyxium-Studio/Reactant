package dev.reactant.reactant.core.component.container

import dev.reactant.reactant.core.ReactantPlugin
import dev.reactant.reactant.core.component.Component
import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult
import org.bukkit.plugin.Plugin
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

class BukkitPluginContainer(val plugin: Plugin) : Container {
    override val componentClasses: Set<KClass<out Any>>
    var _scanResult: ScanResult
    override val scanResult: ScanResult get() = _scanResult

    private val servicePackagesUrl
        get() = plugin.javaClass.getAnnotation(ReactantPlugin::class.java)
            .servicePackages
            .toSet()

    init {
        if (!plugin.javaClass.isAnnotationPresent(ReactantPlugin::class.java)) {
            throw IllegalArgumentException()
        }

        _scanResult = ClassGraph()
            .enableAllInfo()
            .acceptPackages(*servicePackagesUrl.toTypedArray())
            .ignoreParentClassLoaders()
            .overrideClassLoaders(
                plugin.javaClass.classLoader
            )
            .scan()

        componentClasses = getClassesByAnnotation(Component::class)
    }

    override fun getClassesByAnnotation(annotation: KClass<out Annotation>): Set<KClass<out Any>> =
        _scanResult.getClassesWithAnnotation(annotation.jvmName)
            .map { it.loadClass().kotlin }
            .toSet()

    override val displayName: String = plugin.description.name
    override val identifier: String = getIdentifier(plugin)

    companion object {
        @JvmStatic
        fun getIdentifier(plugin: Plugin): String {
            return "bk:${plugin.description.name}"
        }
    }
}
