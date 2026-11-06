package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test

class ModDependencyAdvisorTest {
    private fun mod(id:String,depends:Set<String> = emptySet(),
                    provides:Set<String> = emptySet()) =
        ModInspector.Info(id,"1.0",true,id,depends,provides)

    @Test fun detectsMissingRequiredDependenciesOnly() {
        val findings=ModDependencyAdvisor.missing(listOf(
            mod("farmer",setOf("fabricloader","minecraft","fabric-api","seasons")),
            mod("fabric-api")
        ))
        assertEquals(1,findings.size)
        assertEquals(listOf("seasons"),findings[0].missing)
    }

    @Test fun respectsProvidedAliasesAndBuiltInRuntimeMods() {
        val findings=ModDependencyAdvisor.missing(listOf(
            mod("visuals",setOf("fabricloader","java","minecraft","lib-alias")),
            mod("my-lib",provides=setOf("lib-alias"))
        ))
        assertTrue(findings.isEmpty())
    }

    @Test fun detectsDuplicateModIdsWithoutMutatingInstallations() {
        assertEquals(setOf("mod-a"),ModDependencyAdvisor.duplicateIds(listOf(
            mod("mod-a"),mod("mod-a"),mod("mod-b")
        )))
    }
}
