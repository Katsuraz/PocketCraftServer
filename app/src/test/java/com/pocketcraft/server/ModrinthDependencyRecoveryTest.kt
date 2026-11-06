package com.pocketcraft.server

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ModrinthDependencyRecoveryTest {
    @Test fun missingPinnedLithostitchedAssetUsesCompatibleSameProjectReleaseAfterConsent() {
        var pinnedCalls=0
        var fallbackCalls=0
        var notified=false
        val output=ModrinthDependencyRecovery.attempt(
            pinnedVersionId="pZgI6b2t",
            userApproved=true,
            first={ pinnedCalls++;throw IOException("Download failed (HTTP 404)") },
            compatible={ fallbackCalls++;"Lithostitched 2.0.6 Fabric 26.3" },
            onRecover={notified=true}
        )
        assertEquals("Lithostitched 2.0.6 Fabric 26.3",output)
        assertEquals(1,pinnedCalls)
        assertEquals(1,fallbackCalls)
        assertTrue(notified)
    }

    @Test fun missingVersionMetadataAlsoPermitsFallbackButOnlyWithApproval() {
        val result=ModrinthDependencyRecovery.attempt(
            "deletedPin",true,
            {throw IOException("Download failed (HTTP 410)")},
            {"Available compatible version"}
        )
        assertEquals("Available compatible version",result)
        var retried=false
        try {
            ModrinthDependencyRecovery.attempt(
                "deletedPin",false,
                {throw IOException("Download failed (HTTP 404)")},
                {retried=true;"unsafe"}
            )
            fail("User must approve fallback")
        }catch(expected:IOException){
            assertTrue(expected.message!!.contains("HTTP 404"))
        }
        assertFalse(retried)
    }

    @Test fun doesNotRecoverOnChecksumErrorsOrServerErrors() {
        val messages=listOf("File checksum does not match Modrinth",
            "Download failed (HTTP 500)","Server canceled download")
        messages.forEach { message ->
            var retried=false
            try {
                ModrinthDependencyRecovery.attempt(
                    "pinned123",true,
                    {throw IOException(message)},
                    {retried=true;"wrong"}
                )
                fail("Must never fall back for $message")
            } catch(e:IOException){
                assertEquals(message,e.message)
                assertFalse(retried)
            }
        }
    }

    @Test fun rootWithoutPinAndNestedDependencyErrorsAreNotReplaced() {
        var retried=false
        val err=IOException("Required dependency failed (HTTP 404)")
        try{
            ModrinthDependencyRecovery.attempt(
                "pin",true,
                {throw err},
                {retried=true;"wrong"},
                isNestedDependencyError={true}
            )
            fail("A nested failure must be handled at its own dependency node")
        }catch(e:IOException){assertSame(err,e)}
        assertFalse(retried)
        try {
            ModrinthDependencyRecovery.attempt<String>(
                null,true,{throw IOException("Download failed (HTTP 404)")},
                {retried=true;"wrong"}
            )
            fail("No pin: no alternate release allowed")
        }catch(_:IOException){}
        assertFalse(retried)
    }

    @Test fun failedCompatibleFallbackIsReportedWithoutHidingOriginalIssue() {
        try {
            ModrinthDependencyRecovery.attempt<String>(
                "pin",true,
                {throw IOException("Download failed (HTTP 404)")},
                {throw IOException("No Fabric 26.3 release found")}
            )
            fail("Could not resolve the dependency")
        } catch(e:IOException){
            assertTrue(e.message!!.contains("Pinned release was unavailable"))
            assertTrue(e.message!!.contains("No Fabric 26.3"))
            assertNotNull(e.cause)
        }
    }

    @Test fun validPinnedReleaseIsNeverSubstituted() {
        var fallbackCalls=0
        val value=ModrinthDependencyRecovery.attempt(
            "goodPin",true,{"Pinned required version"},
            {fallbackCalls++;"alternate"}
        )
        assertEquals("Pinned required version",value)
        assertEquals(0,fallbackCalls)
    }
}
