package com.pocketcraft.server

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real on-emulator UI navigation, not screenshots generated from mockups.
 *
 * Tests intentionally never start a Minecraft server, contact Playit, or mutate
 * users' data. This CI-only emulator starts with fresh throwaway application data.
 */
@RunWith(AndroidJUnit4::class)
class PocketCraftUiSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun configureTestEnvironment() {
        context.getSharedPreferences("pocketcraft",0).edit()
            .putBoolean("firstRunGuideSeenV0833",true)
            .putString("gameVersion","26.3")
            .commit()
        device.setOrientationNatural()
        device.waitForIdle()
    }

    private fun launch(type: Class<out Activity>) {
        val intent = Intent(context,type).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instrumentation.startActivitySync(intent)
        device.waitForIdle()
        assertVisible(By.pkg(context.packageName), "PocketCraft screen " + type.simpleName)
    }

    private fun assertVisible(selector: BySelector, description: String): UiObject2 {
        return device.wait(Until.findObject(selector), 15_000L)
            ?: throw AssertionError(description + " was not displayed (check the UI screenshot artifact)")
    }

    private fun capture(name: String) {
        device.waitForIdle()
        val folder = File(context.getExternalFilesDir(null), "ui-smoke")
        if (!folder.exists() && !folder.mkdirs())
            throw AssertionError("Could not create emulator screenshot folder")
        val output = File(folder,name + ".png")
        assertTrue("Failed to capture " + name, device.takeScreenshot(output))
        assertTrue("Empty screenshot " + name, output.length() > 1000L)
        Log.i("PocketCraftUISmoke", "Saved " + output.absolutePath)
    }

    @Test
    fun primaryNavigationSettingsAndModrinthScreensRender() {
        launch(MainActivity::class.java)
        assertVisible(By.descContains("Home tab"), "Home tab")
        capture("01-home")

        assertVisible(By.descContains("Worlds tab"), "Worlds tab").click()
        assertVisible(By.textContains("Your worlds"), "World management page")
        capture("02-worlds")

        assertVisible(By.descContains("Mods tab"), "Mods tab").click()
        assertVisible(By.textContains("Mods & modpacks"), "Mods page")
        capture("03-mods")

        assertVisible(By.descContains("Console tab"), "Console tab").click()
        assertVisible(By.descContains("Console tab, selected"), "Console tab selected")
        capture("04-console")

        assertVisible(By.descContains("Mods tab"), "Mods tab").click()
        val workshop = assertVisible(
            By.textContains("OPEN MODRINTH MODS"), "Modrinth Workshop link")
        workshop.click()
        assertVisible(By.textContains("Modrinth Workshop"), "Modrinth Workshop activity")
        capture("05-modrinth-mods")

        assertVisible(By.text("MODPACKS"), "Modpack browser tab").click()
        assertVisible(By.textContains("Modrinth Workshop"), "Modrinth modpacks browser")
        capture("06-modrinth-modpacks")

        launch(SettingsActivity::class.java)
        capture("07-settings")

        launch(ServerFilesActivity::class.java)
        assertVisible(By.textContains("Server files"), "Server files page")
        capture("08-files")
    }

    /**
     * Live Modrinth API is not a test dependency. Capture the actual version
     * selector when connectivity works; never treat a temporary API outage as
     * a PocketCraft build or UI defect.
     */
    @Test
    fun captureVersionPickerWhenModrinthIsReachable() {
        launch(ModrinthStudioActivity::class.java)
        assertVisible(By.textContains("Modrinth Workshop"), "Modrinth browser")
        val field = assertVisible(By.clazz(EditText::class.java), "Modrinth search field")
        field.text = "Fabric API"
        assertVisible(By.textContains("SEARCH"), "Modrinth search button").click()
        val chooseVersion = device.wait(
            Until.findObject(By.textContains("CHOOSE VERSION")), 25_000L)
        if (chooseVersion == null) {
            Log.i("PocketCraftUISmoke",
                "Modrinth search unavailable in CI; live version picker screenshot skipped")
            capture("09-modrinth-search-state")
            return
        }
        chooseVersion.click()
        val picker = device.wait(
            Until.findObject(By.textContains("Choose Fabric API version")), 25_000L)
        if (picker == null) {
            Log.i("PocketCraftUISmoke",
                "Version metadata unavailable in CI; release dialog screenshot skipped")
            capture("09-modrinth-version-state")
            return
        }
        capture("09-actual-version-picker")
    }
}
