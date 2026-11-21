package com.example.engine

import com.example.model.RootDiagnosticResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RootDiagnostics {

    private val candidateSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/apex/com.android.runtime/bin/su"
    )

    suspend fun runRealRootTest(): RootDiagnosticResult {
        val nowIso = isoNow()
        val foundBinaries = candidateSuPaths.filter { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }

        val idRes = ShellExecutor.runReadOnly("id", useRoot = true, timeoutMs = 4000L, maxLines = 10)
        val uidOk = idRes.isSuccess && idRes.stdout.contains("uid=0")

        val selinuxRes = if (uidOk) {
            ShellExecutor.runReadOnly("getenforce", useRoot = true, timeoutMs = 2000L, maxLines = 4)
        } else {
            ShellExecutor.runReadOnly("getenforce", useRoot = false, timeoutMs = 2000L, maxLines = 4)
        }
        val selinuxMode = selinuxRes.stdout.lines().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: if (selinuxRes.stderr.isNotBlank()) "Restreint (${selinuxRes.stderr.take(40)})" else "Non lisible"

        val detectedManager = if (uidOk) {
            detectRootManagerWithRoot(foundBinaries)
        } else {
            detectRootManagerPassive(foundBinaries)
        }

        val errorDetail = when {
            uidOk -> ""
            idRes.timedOut -> "Expiration lors de la demande su (vérifiez la fenêtre d'autorisation ROOT)."
            idRes.stderr.isNotBlank() -> idRes.stderr
            foundBinaries.isEmpty() -> "Aucun binaire su détecté et appel su -c id refusé."
            else -> "Binaire su présent mais accès refusé (code ${idRes.exitCode})."
        }

        return RootDiagnosticResult(
            testedAtIso = nowIso,
            hasBeenTested = true,
            isRootAvailable = uidOk,
            suUidOutput = idRes.stdout.ifBlank { idRes.stderr.ifBlank { "Aucune sortie" } },
            detectedManager = detectedManager,
            suBinaryPaths = foundBinaries,
            selinuxMode = selinuxMode,
            errorDetail = errorDetail
        )
    }

    private suspend fun detectRootManagerWithRoot(binaries: List<String>): String {
        val magiskRes = ShellExecutor.runReadOnly("magisk -v 2>/dev/null || true", useRoot = true, timeoutMs = 2000L)
        if (magiskRes.stdout.isNotBlank()) return "Magisk (${magiskRes.stdout.lines().first()})"

        val ksuRes = ShellExecutor.runReadOnly("ls /data/adb/ksu 2>/dev/null || true", useRoot = true, timeoutMs = 2000L)
        if (ksuRes.stdout.isNotBlank()) return "KernelSU"

        val apatchRes = ShellExecutor.runReadOnly("ls /data/adb/ap 2>/dev/null || true", useRoot = true, timeoutMs = 2000L)
        if (apatchRes.stdout.isNotBlank()) return "APatch"

        return if (binaries.isNotEmpty()) "su standard (${binaries.first()})" else "su via PATH"
    }

    private fun detectRootManagerPassive(binaries: List<String>): String {
        return when {
            binaries.any { it.contains("ksu") } -> "Indices KernelSU (non élevé)"
            binaries.any { it.contains("/ap/") } -> "Indices APatch (non élevé)"
            binaries.isNotEmpty() -> "Binaire su présent (${binaries.first()})"
            else -> "Aucun gestionnaire détecté"
        }
    }

    fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
}
