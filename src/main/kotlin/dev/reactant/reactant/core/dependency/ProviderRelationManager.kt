package dev.reactant.reactant.core.dependency

import dev.reactant.reactant.core.dependency.injection.producer.Provider
import dev.reactant.reactant.core.dependency.relation.InterpretedProviderRelation
import dev.reactant.reactant.core.exception.CyclicDependencyRelationException

private typealias Dependency = Provider

/**
 * The providers loading order resolver
 */
class ProviderRelationManager {
    private val nodes = HashMap<Dependency, Node>()

    inner class Node(val dependency: Dependency) {
        val requiredBy: HashMap<Node, InterpretedProviderRelation> = HashMap();
        val required: HashMap<Node, InterpretedProviderRelation> = HashMap();
    }

    fun addDependencyRelation(relation: InterpretedProviderRelation) {
        val dependencyNode = relation.interpretTarget.let { nodes.getOrPut(it) { Node(it) } }
        val requiredNode = relation.dependOn.let { nodes.getOrPut(it) { Node(it) } }
        dependencyNode.required[requiredNode] = relation;
        requiredNode.requiredBy[dependencyNode] = relation;
    }

    /**
     * Remove a dependency from manager, and remove requiredBy relation from its parents
     * @throws IllegalStateException If there have any other dependency which required it is still exist
     */
    fun removeDependency(dependency: Dependency) {
        val removingNode = nodes[dependency] ?: return;
        if (nodes.values.any { it.required.contains(removingNode) }) throw IllegalStateException();
        nodes.values.forEach { it.requiredBy.remove(removingNode) }
    }

    /**
     * Get all providers which requiring it
     */
    fun getDependencyChildrenRecursively(dependency: Dependency): Set<Dependency> = nodes[dependency]?.requiredBy
            ?.keys?.flatMap { setOf(it.dependency).union(getDependencyChildrenRecursively(it.dependency)) }
            ?.toSet() ?: setOf()

    fun getDependenciesDepth(): Map<Dependency, Int> {
        val depth = hashMapOf<Dependency, Int>()
        fun getDependencyDepth(dependency: Dependency, fromDependency: Pair<Dependency, InterpretedProviderRelation>? = null, walked: LinkedHashMap<Dependency, InterpretedProviderRelation>): Int {
            if (fromDependency != null) walked[fromDependency.first] = fromDependency.second
            if (walked.contains(dependency)) throw CyclicDependencyRelationException(dependency, walked)
            return (depth[dependency] // if cache exist
                    ?: nodes[dependency]!!.required.map { getDependencyDepth(it.key.dependency, dependency to it.value, LinkedHashMap(walked)) + 1 }.maxOrNull() // from max depth + 1
                    ?: 0) // 0 if no required providers
        }
        nodes.keys.forEach {
            depth.set(it, getDependencyDepth(it, null, LinkedHashMap()))
        }
        return depth
    }
}
