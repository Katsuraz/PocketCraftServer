package com.pocketcraft.server

/**
 * Advisory-only dependency checks from Fabric's installed metadata.
 * Nested JARs and runtime-provided modules can produce false positives,
 * so these findings must never trigger automatic deletion or downloads.
 */
internal object ModDependencyAdvisor {
    data class Finding(val modId:String, val modName:String, val missing:List<String>)
    private val builtIns=setOf("minecraft","java","fabricloader")

    fun missing(installed: List<ModInspector.Info>): List<Finding> {
        val active=installed.filter { it.fabric && !it.modId.isNullOrBlank() }
        val installedIds=active.flatMap { listOfNotNull(it.modId)+it.provides.toList() }
            .toSet()+builtIns
        return active.mapNotNull { mod ->
            val missing=mod.requiredDependencies.filter { it !in installedIds }.sorted()
            if(missing.isEmpty()) null else Finding(mod.modId!!,mod.title,missing)
        }
    }

    fun duplicateIds(installed: List<ModInspector.Info>): Set<String> =
        installed.filter { it.fabric }.mapNotNull { it.modId }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
