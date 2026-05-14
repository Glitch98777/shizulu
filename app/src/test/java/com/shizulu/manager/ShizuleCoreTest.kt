package com.shizulu.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizuleCoreTest {
    @Test
    fun oldSchemaStillParsesWithDefaults() {
        val shizule = Shizule.fromJson(
            """
            {
              "schema": 1,
              "id": "com.example.test",
              "name": "Test",
              "version": "1.2.3",
              "description": "Old format module",
              "actions": [
                {
                  "id": "read",
                  "label": "Read",
                  "commands": [{"exec": "settings get global animator_duration_scale"}]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("com.example.test", shizule.id)
        assertEquals(1002003, shizule.versionCode)
        assertEquals(RestoreSupport.NONE, shizule.restore.level)
        assertTrue(shizule.permissions.isEmpty())
    }

    @Test
    fun validationReportsUndefinedVariables() {
        val result = Shizule.validate(
            """
            {
              "schema": 1,
              "id": "com.example.test",
              "name": "Test",
              "actions": [
                {
                  "id": "run",
                  "label": "Run",
                  "commands": [{"exec": "cmd appops get {{package}}"}]
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.contains("Undefined variable") })
    }

    @Test
    fun scannerBlocksDestructiveAndBypassCommands() {
        val destructive = ShizuleRiskScanner.scanCommand("rm -rf /data")
        val bypass = ShizuleRiskScanner.scanCommand("echo playintegrity bypass")

        assertEquals(RiskLevel.CRITICAL, destructive.level)
        assertTrue(destructive.blocked)
        assertEquals(RiskLevel.CRITICAL, bypass.level)
        assertTrue(bypass.blocked)
    }

    @Test
    fun scannerClassifiesSettingsAndPackageCommands() {
        assertEquals(RiskLevel.LOW, ShizuleRiskScanner.scanCommand("settings get global window_animation_scale").level)
        assertEquals(RiskLevel.MEDIUM, ShizuleRiskScanner.scanCommand("settings put global window_animation_scale 0.5").level)
        assertEquals(RiskLevel.HIGH, ShizuleRiskScanner.scanCommand("pm clear com.example.app").level)
    }

    @Test
    fun restorePlannerCreatesSettingsProbe() {
        val probe = RestorePlanner.probeFor("settings put global animator_duration_scale 0.5")

        assertNotNull(probe)
        assertEquals("settings get global animator_duration_scale", probe!!.command)
        assertEquals("settings put global animator_duration_scale {{value}}", probe.restoreTemplate)
    }

    @Test
    fun trustLabelsShaMismatchHonestly() {
        val shizule = Shizule.fromJson(
            """
            {
              "schema": 1,
              "id": "com.example.signed",
              "name": "Signed",
              "signature": {"author": "A", "sha256": "deadbeef"},
              "actions": [
                {"id": "read", "label": "Read", "commands": [{"exec": "id"}]}
              ]
            }
            """.trimIndent()
        )

        val trust = ShizuleTrust.evaluate(shizule)
        assertEquals(TrustLevel.TAMPERED, trust.level)
        assertTrue(trust.warning)
    }

    @Test
    fun genericReadOnlyModuleIsNotBlocked() {
        val shizule = Shizule.fromJson(
            """
            {
              "schema": 1,
              "id": "com.example.safe",
              "name": "Safe",
              "actions": [
                {"id": "read", "label": "Read", "commands": [{"exec": "dumpsys battery"}]}
              ]
            }
            """.trimIndent()
        )

        val report = ShizuleRiskScanner.scan(shizule)
        assertFalse(report.blocked)
        assertEquals(RiskLevel.LOW, report.level)
    }
}
