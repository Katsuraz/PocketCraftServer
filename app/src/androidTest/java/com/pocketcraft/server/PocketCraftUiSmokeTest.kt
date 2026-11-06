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
        assertVisible(By.text("SORT"),"Server file sort control").click()
        assertVisible(By.textContains("Sort server files"),"Themed file sorting dialog")
        capture("08b-files-sort-dialog")
    }

    /**
     * Deterministic UI regression for the same long version list users see:
     * no network dependency or server process required.
     */
    @Test
    fun modrinthVersionPickerIsStyledFilterableAndScrollable() {
        val screen=instrumentation.startActivitySync(Intent(context,
            ModrinthStudioActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        device.waitForIdle()
        val releases=(0 until 13).map { index ->
            val type=when(index) {
                2 -> "beta"
                4 -> "alpha"
                else -> "release"
            }
            ModrinthCatalog.Version(
                "version"+index,"fabric-api","Fabric API",
                "0."+(165-index)+".2+26.3",type,
                "2026-11-"+(3+index).toString().padStart(2,'0'),
                (52000-index*1000).toLong(),
                if(index==3)2 else 0,
                if(index==3)1 else 0,
                0,"fabric-api.jar","https://cdn.modrinth.com/data/sample.jar",
                null,listOf(if(index==3)"1.20.1" else "26.3"),
                "server_only",listOf(if(index==4)"neoforge" else "fabric")
            )
        }
        instrumentation.runOnMainSync {
            ModrinthVersionPicker.show(screen,"Fabric API","26.3",releases) {}
        }
        assertVisible(By.textContains("Choose Fabric API version"),"Themed version heading")
        assertVisible(By.textContains("0.165.2+26.3"),"Newest version card")
        assertVisible(By.desc("Filter BETA versions"),"Release channel filters")
        capture("10-version-picker-full-list")

        assertVisible(By.desc("Filter BETA versions"),"Beta filter").click()
        assertVisible(By.textContains("0.163.2+26.3"),"Beta version")
        assertVisible(By.textContains("1 of 13"),"Filtered release count")
        capture("11-version-picker-beta")

        assertVisible(By.desc("Filter ALPHA versions"),"Alpha filter").click()
        assertVisible(By.textContains("Loader: neoforge"),"Other loader remains visible")
        assertVisible(By.textContains("MAY NOT WORK"),"Compatibility warning on version card")
        capture("11b-version-picker-incompatible-loader")

        assertVisible(By.desc("Filter ALL versions"),"All versions").click()
        assertVisible(By.textContains("13 of 13"),"All releases")
        assertVisible(By.textContains("ORDER"),"Sort toggle").click()
        assertVisible(By.textContains("oldest first"),"Oldest-first feedback")
        capture("12-version-picker-sorted")
    }

    @Test
    fun releaseOptionsHaveCompactButtonsAndExplicitWarnings() {
        val screen=instrumentation.startActivitySync(Intent(context,
            ModrinthStudioActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }) as ModrinthStudioActivity
        device.waitForIdle()
        val hit=ModrinthCatalog.Hit("8oi3bsk5","terralith","Terralith",
            "World generation","Apollo",1000L,null,"Review compatibility")
        val release=ModrinthCatalog.Version(
            "aGE3hYAA","8oi3bsk5","Terralith","2.6.5+26.3",
            "release","2026-10-14",1200L,1,0,0,
            "terralith-2.6.5+26.3.jar",
            "https://cdn.modrinth.com/data/8oi3bsk5/versions/example.jar",
            "a".repeat(128),listOf("26.3"),"server_only",listOf("fabric"))
        instrumentation.runOnMainSync {
            screen.selectVersion(hit,release,"mod")
        }
        assertVisible(By.text("Release options"),"Release options title")
        assertVisible(By.text("REVIEW INSTALL"),"Review install button")
        assertVisible(By.text("DOWNLOAD ONLY"),"Download-only button")
        assertVisible(By.text("CANCEL"),"Cancel button")
        capture("14-terralith-release-options-compact")
        assertVisible(By.text("CANCEL"),"Cancel action").click()

        instrumentation.runOnMainSync {
            screen.selectVersion(hit,release.copy(gameVersions=listOf("26.2")),"mod")
        }
        assertVisible(By.textContains("Compatibility warning"),"Mismatch warning")
        assertVisible(By.text("INSTALL ANYWAY"),"Explicit compatibility override")
        assertVisible(By.text("DOWNLOAD ONLY"),"Safe download alternative")
        capture("15-terralith-incompatible-warning")
        assertVisible(By.text("CANCEL"),"Cancel mismatch dialog").click()
    }


    /**
     * Regression for the actual Terralith / Lithostitched pinned dependency
     * review. No live API, no installed worlds, no download is performed.
     */
    @Test
    fun pinnedDependencyRecoveryIsVisibleAndUserControlled() {
        val screen=instrumentation.startActivitySync(Intent(context,
            ModrinthStudioActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }) as ModrinthStudioActivity
        val terralith=ModrinthCatalog.Hit(
            "8oi3bsk5","terralith","Terralith",
            "Worldgen mod","Stardust Labs",20000000L,null,"Review version")
        val release=ModrinthCatalog.Version(
            "aGE3hYAA","8oi3bsk5","Terralith 2.6.5",
            "2.6.5+26.3","release","2026-09-27",120000L,
            1,0,0,"Terralith_26.3.jar",
            "https://cdn.modrinth.com/data/example.jar",
            "a".repeat(128),listOf("26.3"),"server_only",listOf("fabric"))
        val lithostitched=WebsiteModInstaller.DependencyChoice(
            "XaDC71GB","Lithostitched",true,false,"pZgI6b2t","required")
        instrumentation.runOnMainSync {
            screen.showChoices(terralith,release,listOf(lithostitched),false)
        }
        val recovery=assertVisible(
            By.textContains("RECOVER UNAVAILABLE PINNED DEPENDENCIES"),
            "Required pinned dependency recovery option")
        assertTrue("Pinned recovery should be selected for this install",recovery.isChecked)
        capture("13-terralith-pinned-dependency-recovery")

        recovery.click()
        assertTrue("User must be able to disable alternative dependency versions",
            !recovery.isChecked)
        capture("14-terralith-recovery-opted-out")
        recovery.click()
        assertTrue("User must be able to reenable dependency fallback",recovery.isChecked)
        assertVisible(By.textContains("INSTALL WITH DEPENDENCIES"),
            "Installer confirmation that does not auto install")
        capture("15-terralith-recovery-opted-in")
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
