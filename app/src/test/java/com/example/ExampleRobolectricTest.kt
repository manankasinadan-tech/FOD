package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FodStorageExporter
import com.example.engine.HalExtractionBundle
import com.example.engine.RawCollectorArtifacts
import com.example.model.DeviceProfile
import com.example.model.FlattenedHalArtifact
import com.example.model.MechanismReconstruction
import com.example.model.PermissionsState
import com.example.model.ReconstructionOutcome
import com.example.model.RomType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FOD Detector", appName)
  }

  @Test
  fun `exporter writes max 3 json plus 1 evidence txt and roundtrips zip`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val exporter = FodStorageExporter(context)
    val sessionId = "session_test_roundtrip"
    val hal = FlattenedHalArtifact(
      id = "hal-1",
      serviceName = "android.hardware.biometrics.fingerprint@2.1::IBiometricsFingerprint/default",
      interfaceType = "HIDL",
      binaryPath = "/vendor/bin/hw/android.hardware.biometrics.fingerprint@2.1-service",
      dtNeededDependencies = listOf("libbinder.so", "libvendor_fod.so"),
      flattenedSourceSpec = "// HAL SPEC"
    )
    val exportState = exporter.exportFullSession(
      sessionId = sessionId,
      profile = DeviceProfile(
        manufacturer = "TestOEM",
        model = "FodPhone",
        androidRelease = "15",
        sdkInt = 35,
        romType = RomType.STOCK_OEM
      ),
      permissions = PermissionsState(),
      baseline = null,
      events = emptyList(),
      components = emptyList(),
      dependencies = emptyList(),
      modifications = emptyList(),
      flattenedHals = listOf(hal),
      reconstruction = MechanismReconstruction(
        outcome = ReconstructionOutcome.ENROLEMENT_REUSSI,
        summary = "Test OK",
        causalExecutionPath = listOf("Settings -> HAL")
      ),
      artifacts = RawCollectorArtifacts(
        matchedProperties = mapOf("ro.hardware.fp.fod" to "true"),
        halBundle = HalExtractionBundle(
          flattenedHals = listOf(hal),
          rcFileContents = mapOf("/vendor/etc/init/fp.rc" to "service vendor.fps_hal"),
          permissionXmlContents = mapOf("/vendor/etc/permissions/fp.xml" to "<feature name=\"android.hardware.fingerprint\"/>")
        )
      )
    )

    val sessionDir = File(exportState.sessionDirectoryPath)
    val jsonFiles = sessionDir.listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty()
    val evidenceFiles = File(sessionDir, "evidence").listFiles()?.filter { it.isFile }.orEmpty()

    assertTrue(jsonFiles.size <= 3)
    assertEquals(3, jsonFiles.size)
    assertEquals(1, evidenceFiles.size)
    assertEquals("all_evidence.txt", evidenceFiles.first().name)

    val zipFile = File(exportState.zipFilePath)
    assertTrue(zipFile.exists() && zipFile.length() > 0L)

    val imported = exporter.importSessionFromLocalZip(zipFile).getOrThrow()
    assertEquals(sessionId, imported.sessionId)
    assertEquals("TestOEM FodPhone", imported.manufacturerModel)
    assertEquals(1, imported.flattenedHals.size)
    assertEquals("true", imported.properties["ro.hardware.fp.fod"])
  }
}
