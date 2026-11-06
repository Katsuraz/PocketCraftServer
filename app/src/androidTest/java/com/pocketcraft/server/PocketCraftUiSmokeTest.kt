package com.pocketcraft.server

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
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
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        // Capture the FIRST rendered frame, including permission prompts or
        // a system crash dialog, so failed launches remain diagnosable.
        capture("00-launch-" + type.simpleName)
        val foreground = device.currentPackageName ?: "unknown"
        Log.i("PocketCraftUISmoke",
            "Launched " + type.simpleName + " foreground=" + foreground +
                " expected=" + context.packageName)
        if (foreground != context.packageName) {
            throw AssertionError(type.simpleName + " is not foreground (got " +
                foreground + "). See the launch screenshot and AndroidRuntime logcat.")
        }
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
        // Android 10+ requires MediaStore for owned files in public Pictures.
        // Files persist after Gradle uninstalls the temporary test APK.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name + ".png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/PocketCraftUISmoke")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
            ?: throw AssertionError("Could not create public emulator screenshot " + name)
        try {
            val bytes = context.contentResolver.openOutputStream(uri)?.use { stream ->
                output.inputStream().use { input -> input.copyTo(stream) }
            } ?: 0L
            assertTrue("Incomplete screenshot export " + name, bytes == output.length())
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING,0)
            }
            assertTrue("Screenshot not published " + name,
                context.contentResolver.update(uri,published,null,null) > 0)
            Log.i("PocketCraftUISmoke","Saved public screenshot " + name + " (" + bytes + " bytes)")
        } catch (error: Exception) {
            context.contentResolver.delete(uri,null,null)
            throw error
        }
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
     * Deterministic offline regression for the dialog pictured in the bug report.
     * Uses realistic sample versions only INSIDE this emulator test, never in
     * production search results or installers.
     */
    @Test
    fun versionPickerHasThemedScrollableRowsAndWorkingFiltersOffline() {
        val target = instrumentation.startActivitySync(Intent(context,
            ModrinthStudioActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }) as ModrinthStudioActivity
        instrumentation.waitForIdleSync()
        val sample = (0 until 24).map { index ->
            ModrinthCatalog.Version(
                id = "test-version-" + index,
                projectId = "P7dR8mSH",
                title = "Fabric API",
                number = if (index == 0) "0.165.2+26.3"
                    else "0." + (165-index) + ".0+26.3",
                releaseType = if (index % 7 == 0) "beta"
                    else if (index % 11 == 0) "alpha" else "release",
                published = "2026-11-03",
                downloads = 2000L - index,
                requiredCount = 0,
                optionalCount = 0,
                incompatibleCount = 0,
                filename = "fabric-api-test.jar",
                url = "https://cdn.modrinth.com/data/test.jar",
                sha512 = null,
                gameVersions = listOf("26.3"),
                environment = "server_only"
            )
        }
        instrumentation.runOnMainSync {
            ModrinthVersionDialog.show(target,"Fabric API","26.3",sample) {}
        }
        assertVisible(By.textContains("Choose Fabric API version"),"Native version picker heading")
        assertVisible(By.textContains("SHOWING 24 OF 24"),"Version count")
        assertVisible(By.desc("Search available versions"),"Version search input")
        capture("10-themed-version-picker")

        val search = assertVisible(By.desc("Search available versions"),"Search versions")
        search.text = "0.165.2"
        assertVisible(By.textContains("SHOWING 1 OF 24"),"Version filtering updates results")
        assertVisible(By.textContains("0.165.2+26.3"),"Matching release remains visible")
        capture("11-version-search-filter")

        search.text = ""
        assertVisible(By.desc("BETA versions"),"Beta release filter").click()
        assertVisible(By.textContains("SHOWING 4 OF 24"),"Beta versions are filtered")
        capture("12-beta-version-filter")
    }

    @Test
    fun fileManagerSortDialogCanBeOpenedFromRealScreen() {
        launch(ServerFilesActivity::class.java)
        var sort = device.findObject(By.textContains("SORT: NAME"))
        repeat(5) {
            if (sort == null) {
                device.swipe(540,1850,540,580,20)
                device.waitForIdle()
                sort = device.findObject(By.textContains("SORT: NAME"))
            }
        }
        val button = sort ?: throw AssertionError("File sorting button is not reachable")
        button.click()
        assertVisible(By.textContains("Sort files"),"File sorting chooser")
        capture("13-file-sort-dialog")
        assertVisible(By.textContains("Modified (newest first)"),
            "Sort by most recent modification").click()
        assertVisible(By.textContains("Server files"),"File browser after sorting")
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
