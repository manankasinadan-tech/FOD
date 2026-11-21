package com.example.engine

import com.example.model.EvidenceLevel
import com.example.model.FlattenedHalArtifact
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

data class ElfInspectionResult(
    val isValidElf64: Boolean = false,
    val dtNeeded: List<String> = emptyList(),
    val exportedSymbols: List<String> = emptyList(),
    val extractedStrings: List<String> = emptyList()
)

data class HalExtractionBundle(
    val flattenedHals: List<FlattenedHalArtifact> = emptyList(),
    val rcFileContents: Map<String, String> = emptyMap(),
    val permissionXmlContents: Map<String, String> = emptyMap(),
    val vintfManifestContents: Map<String, String> = emptyMap(),
    val selinuxContextLines: List<String> = emptyList()
)

/**
 * Inspecteur binaire ELF64 & extracteur HAL/RC/Permissions en lecture seule.
 * Fonctionne de façon autonome même si readelf/nm/ldd sont absents du système.
 */
object ElfAndHalInspector {

    private val halIdCounter = AtomicInteger(1)
    private val elfCache = LinkedHashMap<String, ElfInspectionResult>()

    private val relevantStringRegex = Regex(
        "(/sys/[a-zA-Z0-9_./-]+|/dev/[a-zA-Z0-9_./-]+|/proc/[a-zA-Z0-9_./-]+|android\\.hardware\\.biometrics\\.fingerprint[@a-zA-Z0-9._:-]*|vendor\\.[a-zA-Z0-9._:-]*fingerprint[a-zA-Z0-9._:-]*|vendor\\.[a-zA-Z0-9._:-]*fod[a-zA-Z0-9._:-]*|fod|udfps|lhbm|hbm|dimlayer|goodix|silead|egistec|focaltech|gx_fpd|qsee)",
        RegexOption.IGNORE_CASE
    )

    private val symbolInterestRegex = Regex(
        "fp|finger|fod|udfps|enroll|auth|hbm|lhbm|dim|sensor|goodix|silead|fpc|egistec|focaltech|hal|binder|hidl|aidl|ioctl|notify|acquire|verify",
        RegexOption.IGNORE_CASE
    )

    fun parseElf64ReadOnly(bytes: ByteArray): ElfInspectionResult {
        if (bytes.size < 64) return ElfInspectionResult()
        // Vérification magie ELF + 64-bit (EI_CLASS = 2) + Little Endian (EI_DATA = 1)
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
            bytes[4] != 2.toByte() || bytes[5] != 1.toByte()
        ) {
            return ElfInspectionResult()
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eShoff = buf.getLong(40).toInt()
        val eShentsize = buf.getShort(58).toInt() and 0xFFFF
        val eShnum = buf.getShort(60).toInt() and 0xFFFF
        val eShstrndx = buf.getShort(62).toInt() and 0xFFFF

        if (eShoff <= 0 || eShentsize < 64 || eShnum <= 0 || eShoff + eShentsize * eShnum > bytes.size) {
            return ElfInspectionResult(
                isValidElf64 = true,
                extractedStrings = extractPrintableStrings(bytes)
            )
        }

        data class SectionHeader(
            val nameOffset: Int,
            val type: Int,
            val offset: Int,
            val size: Int,
            val link: Int,
            val entsize: Int
        )

        val sections = (0 until eShnum).map { idx ->
            val base = eShoff + idx * eShentsize
            SectionHeader(
                nameOffset = buf.getInt(base),
                type = buf.getInt(base + 4),
                offset = buf.getLong(base + 24).toInt(),
                size = buf.getLong(base + 32).toInt(),
                link = buf.getInt(base + 40),
                entsize = buf.getLong(base + 56).toInt()
            )
        }

        val shstrSection = sections.getOrNull(eShstrndx)
        fun sectionName(sh: SectionHeader): String {
            if (shstrSection == null) return ""
            return readCString(bytes, shstrSection.offset + sh.nameOffset, shstrSection.offset + shstrSection.size)
        }

        val dtNeeded = mutableListOf<String>()
        val exportedSymbols = mutableListOf<String>()

        // SHT_DYNAMIC = 6
        val dynSection = sections.firstOrNull { it.type == 6 }
        if (dynSection != null && dynSection.link in sections.indices && dynSection.offset >= 0 && dynSection.offset + dynSection.size <= bytes.size) {
            val dynstr = sections[dynSection.link]
            if (dynstr.offset >= 0 && dynstr.offset + dynstr.size <= bytes.size) {
                var pos = dynSection.offset
                val end = dynSection.offset + dynSection.size
                while (pos + 16 <= end) {
                    val dTag = buf.getLong(pos)
                    val dVal = buf.getLong(pos + 8)
                    if (dTag == 0L) break
                    if (dTag == 1L) { // DT_NEEDED
                        val lib = readCString(bytes, dynstr.offset + dVal.toInt(), dynstr.offset + dynstr.size)
                        if (lib.isNotBlank()) dtNeeded.add(lib)
                    }
                    pos += 16
                }
            }
        }

        // SHT_DYNSYM = 11
        val dynsymSection = sections.firstOrNull { it.type == 11 }
        if (dynsymSection != null && dynsymSection.link in sections.indices && dynsymSection.offset >= 0 && dynsymSection.offset + dynsymSection.size <= bytes.size) {
            val dynstr = sections[dynsymSection.link]
            val entSize = if (dynsymSection.entsize >= 24) dynsymSection.entsize else 24
            if (dynstr.offset >= 0 && dynstr.offset + dynstr.size <= bytes.size) {
                var pos = dynsymSection.offset
                val end = dynsymSection.offset + dynsymSection.size
                while (pos + entSize <= end && exportedSymbols.size < 160) {
                    val stName = buf.getInt(pos)
                    val stInfo = bytes[pos + 4].toInt() and 0xFF
                    val stShndx = buf.getShort(pos + 6).toInt() and 0xFFFF
                    val bind = stInfo ushr 4
                    if (stShndx != 0 && (bind == 1 || bind == 2) && stName > 0) {
                        val sym = readCString(bytes, dynstr.offset + stName, dynstr.offset + dynstr.size)
                        if (sym.isNotBlank() && symbolInterestRegex.containsMatchIn(sym)) {
                            exportedSymbols.add(sym)
                        }
                    }
                    pos += entSize
                }
            }
        }

        // Chaînes pertinentes dans .rodata ou l'ensemble des sections de données lisibles
        val rodataStrings = mutableListOf<String>()
        val targetSections = sections.filter {
            val n = sectionName(it)
            n == ".rodata" || n == ".data.rel.ro" || n == ".cstring"
        }
        if (targetSections.isNotEmpty()) {
            targetSections.forEach { sec ->
                if (sec.offset >= 0 && sec.size > 0 && sec.offset + sec.size <= bytes.size) {
                    val slice = bytes.copyOfRange(sec.offset, minOf(sec.offset + sec.size, sec.offset + 512 * 1024))
                    rodataStrings.addAll(extractPrintableStrings(slice))
                }
            }
        } else {
            rodataStrings.addAll(extractPrintableStrings(bytes))
        }

        return ElfInspectionResult(
            isValidElf64 = true,
            dtNeeded = dtNeeded.distinct(),
            exportedSymbols = exportedSymbols.distinct().take(80),
            extractedStrings = rodataStrings.distinct().take(80)
        )
    }

    private fun readCString(bytes: ByteArray, start: Int, maxEnd: Int): String {
        if (start < 0 || start >= bytes.size || start >= maxEnd) return ""
        val endLimit = minOf(bytes.size, maxEnd, start + 256)
        var end = start
        while (end < endLimit && bytes[end] != 0.toByte()) {
            end++
        }
        return String(bytes, start, end - start, Charsets.UTF_8)
    }

    private fun extractPrintableStrings(bytes: ByteArray): List<String> {
        val found = LinkedHashSet<String>()
        val sb = StringBuilder()
        val limit = minOf(bytes.size, 1024 * 1024)
        for (i in 0 until limit) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 32..126) {
                sb.append(b.toChar())
            } else {
                if (sb.length >= 6) {
                    val s = sb.toString()
                    if (relevantStringRegex.containsMatchIn(s) && s.length <= 180) {
                        found.add(s)
                        if (found.size >= 80) break
                    }
                }
                sb.setLength(0)
            }
        }
        return found.toList()
    }

    suspend fun extractAndFlattenHals(
        useRoot: Boolean,
        discoveredServices: List<String>,
        discoveredHalFiles: List<String>,
        discoveredInitLines: List<String>
    ): HalExtractionBundle {
        // 1. Extraire les fichiers .rc complets liés au biométrique / FOD
        val rcPaths = discoveredInitLines
            .map { it.substringBefore(":").trim() }
            .filter { it.endsWith(".rc") }
            .distinct()
            .toMutableList()

        if (rcPaths.isEmpty()) {
            val findRc = ShellExecutor.runReadOnly(
                "grep -rlIE 'fingerprint|biometric|fod|udfps|goodix|silead|fpc|egistec' /vendor/etc/init /system/etc/init /system_ext/etc/init /odm/etc/init 2>/dev/null | head -n 20 || true",
                useRoot = useRoot,
                timeoutMs = 3500L
            )
            findRc.stdout.lineSequence().map { it.trim() }.filter { it.endsWith(".rc") }.forEach { rcPaths.add(it) }
        }

        val rcContents = linkedMapOf<String, String>()
        for (rcPath in rcPaths.distinct().take(15)) {
            val txt = readTextFileReadOnly(rcPath, useRoot, 16000)
            if (!txt.isNullOrBlank()) rcContents[rcPath] = txt
        }

        // 2. Extraire les fichiers de permissions XML biométriques / FOD
        val permXmlPaths = mutableListOf<String>()
        val findXml = ShellExecutor.runReadOnly(
            "ls -1 /vendor/etc/permissions/*finger* /vendor/etc/permissions/*bio* /vendor/etc/permissions/*fod* /system/etc/permissions/*finger* /system/etc/permissions/*bio* /odm/etc/permissions/*finger* /system_ext/etc/permissions/*finger* 2>/dev/null || true",
            useRoot = useRoot,
            timeoutMs = 3000L
        )
        findXml.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".xml") && !it.contains("No such file") }
            .forEach { permXmlPaths.add(it) }

        val permissionXmlContents = linkedMapOf<String, String>()
        for (xp in permXmlPaths.distinct().take(15)) {
            val txt = readTextFileReadOnly(xp, useRoot, 16000)
            if (!txt.isNullOrBlank()) permissionXmlContents[xp] = txt
        }

        // 3. Extraire les manifests VINTF liés à la biométrie
        val vintfPaths = mutableListOf<String>()
        val findVintf = ShellExecutor.runReadOnly(
            "grep -rlIE 'fingerprint|biometrics' /vendor/etc/vintf /odm/etc/vintf /system/etc/vintf /system_ext/etc/vintf 2>/dev/null | head -n 12 || true",
            useRoot = useRoot,
            timeoutMs = 3000L
        )
        findVintf.stdout.lineSequence().map { it.trim() }.filter { it.endsWith(".xml") }.forEach { vintfPaths.add(it) }
        val vintfContents = linkedMapOf<String, String>()
        for (vp in vintfPaths.distinct().take(10)) {
            val txt = readTextFileReadOnly(vp, useRoot, 20000)
            if (!txt.isNullOrBlank()) vintfContents[vp] = txt
        }

        // 4. Contextes SELinux liés au capteur FOD
        val seRes = ShellExecutor.runReadOnly(
            "grep -iE 'fingerprint|fod|udfps|goodix|silead|fpc|egistec|gx_fpd' /vendor/etc/selinux/vendor_file_contexts /system/etc/selinux/plat_file_contexts /vendor/etc/selinux/vendor_service_contexts /system/etc/selinux/plat_service_contexts 2>/dev/null | head -n 60 || true",
            useRoot = useRoot,
            timeoutMs = 3000L,
            maxLines = 80
        )
        val selinuxLines = seRes.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

        // 5. Inspecter les binaires HAL et bibliothèques .so via ELF64 (avec cache)
        val elfResults = linkedMapOf<String, ElfInspectionResult>()
        val candidateBinaries = discoveredHalFiles.distinct().take(18)
        for (binPath in candidateBinaries) {
            val cached = elfCache[binPath]
            if (cached != null) {
                elfResults[binPath] = cached
            } else {
                val bytes = ShellExecutor.readBinaryReadOnly(binPath, useRoot = useRoot)
                if (bytes != null) {
                    val parsed = parseElf64ReadOnly(bytes)
                    elfCache[binPath] = parsed
                    elfResults[binPath] = parsed
                }
            }
        }

        // 6. Construire les fiches HAL mises à plat type "code source de portage"
        val flattened = mutableListOf<FlattenedHalArtifact>()
        val halServiceNames = discoveredServices
            .filter {
                it.contains("fingerprint", true) || it.contains("biometrics", true) ||
                    it.contains("goodix", true) || it.contains("silead", true) ||
                    it.contains("fpc", true) || it.contains("egistec", true) || it.contains("fod", true)
            }
            .distinct()

        val binariesList = candidateBinaries.filter { it.contains("/bin/hw/") || it.endsWith("service") || it.contains("daemon") }
        val libsList = candidateBinaries.filter { it.endsWith(".so") }

        val primaryKeys = if (halServiceNames.isNotEmpty()) halServiceNames else listOf("android.hardware.biometrics.fingerprint (détecté via fichiers/RC)")
        primaryKeys.take(8).forEachIndexed { idx, svc ->
            val cleanSvc = svc.removePrefix("HAL: ").substringBefore(" (").trim()
            val ifaceType = when {
                cleanSvc.contains("@") -> "HIDL (${cleanSvc.substringAfter("@").substringBefore("::")})"
                cleanSvc.contains("aidl", true) || cleanSvc.contains("/default") -> "AIDL"
                svc.startsWith("VINTF(") -> "VINTF Manifest"
                else -> "Service / Daemon Natif"
            }

            val matchedBin = binariesList.getOrNull(idx) ?: binariesList.firstOrNull() ?: "(binaire non exposé dans /bin/hw)"
            val relatedLibs = libsList.filter { lib ->
                val token = cleanSvc.substringAfterLast(".").substringBefore("@").lowercase()
                token.length >= 4 && lib.lowercase().contains(token) ||
                    listOf("fingerprint", "goodix", "silead", "fpc", "egistec", "fod").any { k ->
                        cleanSvc.contains(k, true) && lib.contains(k, true)
                    }
            }.ifEmpty { if (idx == 0) libsList.take(8) else emptyList() }

            val allInspectTargets = (listOf(matchedBin) + relatedLibs).filter { it.startsWith("/") }
            val dtNeeded = allInspectTargets.flatMap { elfResults[it]?.dtNeeded.orEmpty() }.distinct()
            val symbols = allInspectTargets.flatMap { elfResults[it]?.exportedSymbols.orEmpty() }.distinct().take(30)
            val nodeRefs = allInspectTargets.flatMap { elfResults[it]?.extractedStrings.orEmpty() }
                .filter { it.startsWith("/sys/") || it.startsWith("/dev/") || it.startsWith("/proc/") }
                .distinct()

            val matchingRcBlocks = rcContents.entries.mapNotNull { (path, content) ->
                val lines = content.lines().filter {
                    it.contains("fingerprint", true) || it.contains("fod", true) ||
                        it.contains("goodix", true) || it.contains("silead", true) ||
                        it.contains("fpc", true) || it.contains("service ", true)
                }
                if (lines.isNotEmpty()) "// $path\n${content.trim()}" else null
            }

            val matchingPerms = permissionXmlContents.entries.map { (p, content) -> "$p:\n${content.trim()}" }
            val matchingVintf = vintfContents.entries.map { (p, content) -> "$p:\n${content.trim()}" }

            val sourceSpec = buildFlattenedSourceSpec(
                serviceName = cleanSvc,
                interfaceType = ifaceType,
                binaryPath = matchedBin,
                sharedLibraries = relatedLibs,
                dtNeeded = dtNeeded,
                exportedSymbols = symbols,
                referencedNodes = nodeRefs,
                rcBlocks = matchingRcBlocks,
                permissionEntries = matchingPerms,
                selinuxLines = selinuxLines
            )

            flattened.add(
                FlattenedHalArtifact(
                    id = "hal-${halIdCounter.getAndIncrement()}",
                    serviceName = cleanSvc,
                    interfaceType = ifaceType,
                    binaryPath = matchedBin,
                    sharedLibraries = relatedLibs,
                    dtNeededDependencies = dtNeeded,
                    exportedSymbols = symbols,
                    referencedNodes = nodeRefs,
                    initRcBlocks = matchingRcBlocks,
                    permissionEntries = matchingPerms,
                    selinuxContexts = selinuxLines.take(25),
                    vintfEntries = matchingVintf,
                    flattenedSourceSpec = sourceSpec,
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE
                )
            )
        }

        return HalExtractionBundle(
            flattenedHals = flattened,
            rcFileContents = rcContents,
            permissionXmlContents = permissionXmlContents,
            vintfManifestContents = vintfContents,
            selinuxContextLines = selinuxLines
        )
    }

    private fun buildFlattenedSourceSpec(
        serviceName: String,
        interfaceType: String,
        binaryPath: String,
        sharedLibraries: List<String>,
        dtNeeded: List<String>,
        exportedSymbols: List<String>,
        referencedNodes: List<String>,
        rcBlocks: List<String>,
        permissionEntries: List<String>,
        selinuxLines: List<String>
    ): String = buildString {
        appendLine("// ============================================================")
        appendLine("// SPÉCIFICATION HAL MISE À PLAT (LECTURE SEULE — PORTAGE FOD)")
        appendLine("// ============================================================")
        appendLine("interface $serviceName /* Type: $interfaceType */ {")
        appendLine("    binary = \"$binaryPath\";")
        appendLine()
        appendLine("    // Bibliothèques HAL / Vendor associées:")
        if (sharedLibraries.isEmpty()) {
            appendLine("    // (aucune bibliothèque .so spécifique listée)")
        } else {
            sharedLibraries.forEach { appendLine("    include_lib \"$it\";") }
        }
        appendLine()
        appendLine("    // Dépendances dynamiques ELF (DT_NEEDED):")
        if (dtNeeded.isEmpty()) {
            appendLine("    // (lecture ELF64 non disponible sans ROOT)")
        } else {
            dtNeeded.forEach { appendLine("    needs \"$it\";") }
        }
        appendLine()
        appendLine("    // Symboles biométriques / FOD exportés:")
        if (exportedSymbols.isEmpty()) {
            appendLine("    // (aucun symbole dynsym extrait)")
        } else {
            exportedSymbols.take(25).forEach { appendLine("    fn $it();") }
        }
        appendLine()
        appendLine("    // Points d'accès Kernel / Sysfs / Dev référencés dans le binaire:")
        if (referencedNodes.isEmpty()) {
            appendLine("    // (aucune chaîne /sys|/dev|/proc extraite)")
        } else {
            referencedNodes.forEach { appendLine("    node \"$it\";") }
        }
        appendLine("}")
        if (rcBlocks.isNotEmpty()) {
            appendLine()
            appendLine("// --- SCRIPTS INIT (.rc) ---")
            rcBlocks.take(3).forEach { appendLine(it) }
        }
        if (permissionEntries.isNotEmpty()) {
            appendLine()
            appendLine("// --- PERMISSIONS XML ---")
            permissionEntries.take(3).forEach { appendLine(it) }
        }
        if (selinuxLines.isNotEmpty()) {
            appendLine()
            appendLine("// --- CONTEXTES SELINUX ---")
            selinuxLines.take(15).forEach { appendLine(it) }
        }
    }

    private suspend fun readTextFileReadOnly(path: String, useRoot: Boolean, maxChars: Int): String? {
        val local = File(path)
        if (local.exists() && local.canRead()) {
            return runCatching { local.readText().take(maxChars) }.getOrNull()
        }
        if (!useRoot) return null
        val res = ShellExecutor.runReadOnly("head -c $maxChars '$path' 2>/dev/null || true", useRoot = true, timeoutMs = 2000L, maxLines = 350)
        return res.stdout.takeIf { it.isNotBlank() && !it.contains("Permission denied", true) }
    }
}
