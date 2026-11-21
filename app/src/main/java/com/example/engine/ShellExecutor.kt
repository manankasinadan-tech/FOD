package com.example.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ShellResult(
    val command: String,
    val usedRoot: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val blockedReason: String? = null
) {
    val isSuccess: Boolean get() = !timedOut && blockedReason == null && exitCode == 0
}

/**
 * Exécuteur shell strictement en lecture seule.
 * Refuse toute commande susceptible de modifier le système, le vendor, le kernel ou les données biométriques.
 */
object ShellExecutor {

    private val forbiddenTokens = listOf(
        "rm ", "rm\t", "mv ", "cp ", "dd ", "mkfs", "mount", "umount",
        "chmod", "chown", "chcon", "restorecon", "setenforce", "setprop",
        "resetprop ", "insmod", "rmmod", "modprobe", "flash", "reboot",
        "shutdown", "kill ", "pkill", "killall", "pm disable", "pm uninstall",
        "pm clear", "cmd overlay enable", "cmd overlay disable", "tee ",
        "/data/vendor_de", "/data/system/users/0/fpdata", ">", ">>"
    )

    fun isCommandStrictlyReadOnly(command: String): String? {
        val normalized = " ${command.lowercase()} "
        for (token in forbiddenTokens) {
            if (normalized.contains(token)) {
                return "Commande bloquée par la politique passive (lecture seule stricte) : motif '$token'"
            }
        }
        return null
    }

    suspend fun runReadOnly(
        command: String,
        useRoot: Boolean = false,
        timeoutMs: Long = 4500L,
        maxLines: Int = 600
    ): ShellResult = withContext(Dispatchers.IO) {
        val violation = isCommandStrictlyReadOnly(command)
        if (violation != null) {
            return@withContext ShellResult(
                command = command,
                usedRoot = useRoot,
                exitCode = -1,
                stdout = "",
                stderr = violation,
                blockedReason = violation
            )
        }

        val argv = if (useRoot) {
            arrayOf("su", "-c", command)
        } else {
            arrayOf("sh", "-c", command)
        }

        var process: Process? = null
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                process = ProcessBuilder(*argv)
                    .redirectErrorStream(false)
                    .start()

                val p = process!!
                val outBuilder = StringBuilder()
                val errBuilder = StringBuilder()

                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    var count = 0
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (count < maxLines) {
                            outBuilder.append(line).append('\n')
                        }
                        count++
                    }
                }

                BufferedReader(InputStreamReader(p.errorStream)).use { reader ->
                    var count = 0
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (count < 120) {
                            errBuilder.append(line).append('\n')
                        }
                        count++
                    }
                }

                p.waitFor(1500, TimeUnit.MILLISECONDS)
                val code = runCatching { p.exitValue() }.getOrElse { -1 }
                ShellResult(
                    command = command,
                    usedRoot = useRoot,
                    exitCode = code,
                    stdout = outBuilder.toString().trimEnd(),
                    stderr = errBuilder.toString().trimEnd()
                )
            }

            result ?: run {
                runCatching { process?.destroyForcibly() }
                ShellResult(
                    command = command,
                    usedRoot = useRoot,
                    exitCode = -1,
                    stdout = "",
                    stderr = "Délai dépassé (${timeoutMs} ms)",
                    timedOut = true
                )
            }
        } catch (e: Exception) {
            runCatching { process?.destroyForcibly() }
            ShellResult(
                command = command,
                usedRoot = useRoot,
                exitCode = -1,
                stdout = "",
                stderr = e.localizedMessage ?: "Erreur d'exécution shell"
            )
        }
    }

    /**
     * Lecture binaire strictement en lecture seule (bornée à maxBytes) pour l'analyse ELF64 en mémoire.
     */
    suspend fun readBinaryReadOnly(
        path: String,
        useRoot: Boolean,
        maxBytes: Int = 3 * 1024 * 1024
    ): ByteArray? = withContext(Dispatchers.IO) {
        val cleanPath = path.trim()
        if (cleanPath.isBlank() || cleanPath.contains("'") || cleanPath.contains(";") || cleanPath.contains("|")) {
            return@withContext null
        }
        val localFile = java.io.File(cleanPath)
        if (localFile.exists() && localFile.isFile && localFile.canRead()) {
            return@withContext runCatching {
                localFile.inputStream().use { input ->
                    readBoundedBytes(input, maxBytes)
                }
            }.getOrNull()
        }
        if (!useRoot) return@withContext null

        var process: Process? = null
        try {
            withTimeoutOrNull(4500L) {
                process = ProcessBuilder("su", "-c", "cat '$cleanPath'")
                    .redirectErrorStream(false)
                    .start()
                val p = process!!
                val bytes = p.inputStream.use { readBoundedBytes(it, maxBytes) }
                p.waitFor(1200, TimeUnit.MILLISECONDS)
                bytes.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { process?.destroyForcibly() }
        }
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buf.size, maxBytes - total)
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
