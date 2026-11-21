package com.example.model

enum class RomType(val label: String) {
    STOCK_OEM("ROM Constructeur (Stock)"),
    GSI_TREBLE("Image Système Générique (GSI / Treble)"),
    CUSTOM_ROM("ROM Custom"),
    INDETERMINE("Architecture ROM indéterminée")
}

enum class EvidenceLevel(val label: String) {
    FAIT_VERIFIE("FAIT VÉRIFIÉ"),
    CORRELATION_OBSERVEE("CORRÉLATION OBSERVÉE"),
    NON_OBSERVE_LIMITATION("LIMITATION / NON OBSERVÉ")
}

enum class EnginePhase(val label: String, val description: String) {
    INITIALIZING(
        "Initialisation",
        "Reconnaissance de l'appareil, vérification des capacités et capture de l'état initial."
    ),
    BASELINE_CAPTURED(
        "État initial capturé",
        "Instantané de référence enregistré avant toute interaction biométrique."
    ),
    MONITORING_PASSIVE(
        "Surveillance passive active",
        "En attente d'un événement lié à l'enregistrement d'une empreinte dans les Paramètres Android."
    ),
    INVESTIGATING(
        "Investigation approfondie",
        "Événement d'enrôlement détecté : découverte adaptative et corrélation causale en cours."
    ),
    RECONSTRUCTED(
        "Reconstruction terminée",
        "Cartographie factuelle du mécanisme générée et exportée."
    ),
    PAUSED(
        "Surveillance en pause",
        "La collecte passive est suspendue par l'utilisateur."
    )
}

enum class ComponentCategory(val label: String) {
    SERVICE_HAL("Service HAL / VINTF"),
    BINDER_SERVICE("Service Binder / Système"),
    NATIVE_LIB("Bibliothèque native"),
    SYSFS_NODE("Nœud Kernel / Sysfs / Proc"),
    PROPERTY("Propriété système"),
    OVERLAY_RRO("Overlay RRO / Ressource"),
    KERNEL_MODULE("Module / Pilote Kernel"),
    FRAMEWORK_CLASS("Composant Framework"),
    SYSTEM_APP("Paquet / Service Biométrique"),
    PERMISSION_XML("Permission / Manifest XML")
}

enum class MechanismLayer(val label: String, val order: Int) {
    SETTINGS_UI("1. Interface Paramètres & Enrôlement", 1),
    FRAMEWORK("2. Framework Biometric / SystemUI", 2),
    HAL_BINDER("3. HAL Biométrique & IPC (HIDL/AIDL)", 3),
    DISPLAY_ILLUMINATION("4. Affichage / HBM / LHBM / DimLayer", 4),
    TOUCH_DRIVER("5. Tactile / Zone FOD & État Capteur", 5),
    KERNEL_TEE("6. Kernel / Pilotes / TEE & Hooks", 6)
}

enum class ModificationType(val label: String) {
    MAGISK_MODULE("Module Magisk"),
    KERNELSU_MODULE("Module KernelSU / APatch"),
    LSPOSED_HOOK("Hook LSPosed / Xposed"),
    GSI_SHIM("Solution / Shim GSI (Treble)"),
    RRO_OVERLAY("Overlay RRO"),
    INIT_SCRIPT("Script Init / Service.d"),
    CUSTOM_PROP("Propriété / Surcharge FOD"),
    KERNEL_PATCH("Interface / Patch Kernel")
}

enum class StageStatus(val label: String) {
    VERIFIED_ACTIVE("Vérifié actif"),
    CORRELATED("Corrélé"),
    MISSING_OR_REPLACED("Absent ou remplacé"),
    FIRST_BREAK("Première rupture démontrable"),
    UNOBSERVED_LIMITED("Non observable (limitation)")
}

enum class ReconstructionOutcome(val label: String) {
    ENROLEMENT_REUSSI("Enregistrement d'empreinte réussi"),
    ECHEC_DETECTE("Échec ou rupture détectée"),
    SURVEILLANCE_EN_COURS("Observation en cours"),
    OBSERVATION_PARTIELLE("Observation partielle (sans résultat final)")
}

data class RootDiagnosticResult(
    val testedAtIso: String = "",
    val hasBeenTested: Boolean = false,
    val isRootAvailable: Boolean = false,
    val suUidOutput: String = "",
    val detectedManager: String = "Aucun vérifié",
    val suBinaryPaths: List<String> = emptyList(),
    val selinuxMode: String = "Inconnu",
    val errorDetail: String = ""
)

data class CapabilityLimitation(
    val code: String,
    val title: String,
    val impact: String,
    val requiresRoot: Boolean = false,
    val requiresStorage: Boolean = false
)

data class PermissionsState(
    val storagePermissionGranted: Boolean = false,
    val sdcardFodWritable: Boolean = false,
    val activeStorageRootPath: String = "/sdcard/FOD",
    val fallbackStoragePath: String = "",
    val rootEnabledByUser: Boolean = false,
    val rootDiagnostic: RootDiagnosticResult = RootDiagnosticResult(),
    val notificationsGranted: Boolean = false,
    val isForegroundServiceActive: Boolean = false,
    val limitations: List<CapabilityLimitation> = emptyList()
) {
    val isEffectiveRootReady: Boolean
        get() = rootEnabledByUser && rootDiagnostic.isRootAvailable
}

data class DeviceProfile(
    val brand: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val device: String = "",
    val board: String = "",
    val hardware: String = "",
    val socModel: String = "",
    val abis: List<String> = emptyList(),
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val securityPatch: String = "",
    val buildFingerprint: String = "",
    val buildType: String = "",
    val buildTags: String = "",
    val romType: RomType = RomType.INDETERMINE,
    val romEvidence: List<String> = emptyList(),
    val selinuxMode: String = "Inconnu",
    val hasFingerprintFeature: Boolean = false,
    val biometricStatusLabel: String = "",
    val biometricStatusCode: Int = -1,
    val displayResolution: String = "",
    val displayRefreshRateHz: Float = 0f,
    val displayHdrTypes: List<String> = emptyList()
)

data class BaselineSnapshot(
    val timestampIso: String = "",
    val biometricStatusCode: Int = -1,
    val biometricStatusLabel: String = "",
    val screenBrightness: Int = -1,
    val refreshRateHz: Float = 0f,
    val matchedPropertiesCount: Int = 0,
    val readableNodesCount: Int = 0,
    val keyProperties: Map<String, String> = emptyMap(),
    val readableSysfsSnapshot: Map<String, String> = emptyMap()
)

data class FodEvent(
    val id: String,
    val timestampMs: Long,
    val timestampIso: String,
    val source: String,
    val category: String,
    val title: String,
    val rawPayload: String,
    val evidenceLevel: EvidenceLevel,
    val triggersInvestigation: Boolean = false,
    val matchedSignals: List<String> = emptyList()
)

data class DiscoveredComponent(
    val id: String,
    val name: String,
    val category: ComponentCategory,
    val layer: MechanismLayer,
    val pathOrIdentifier: String,
    val status: String,
    val evidenceLevel: EvidenceLevel,
    val rawProof: String,
    val discoveryWave: Int = 0
)

data class ComponentDependency(
    val id: String,
    val fromComponentName: String,
    val toComponentName: String,
    val relationType: String,
    val evidenceLevel: EvidenceLevel,
    val rawProof: String
)

data class DetectedModification(
    val id: String,
    val type: ModificationType,
    val name: String,
    val target: String,
    val roleSummary: String,
    val evidenceLevel: EvidenceLevel,
    val rawProof: String
)

/**
 * Représentation mise à plat d'un HAL biométrique/FOD sous forme de fiche pseudo-source pour portage.
 */
data class FlattenedHalArtifact(
    val id: String,
    val serviceName: String,
    val interfaceType: String,
    val binaryPath: String,
    val sharedLibraries: List<String> = emptyList(),
    val dtNeededDependencies: List<String> = emptyList(),
    val exportedSymbols: List<String> = emptyList(),
    val referencedNodes: List<String> = emptyList(),
    val initRcBlocks: List<String> = emptyList(),
    val permissionEntries: List<String> = emptyList(),
    val selinuxContexts: List<String> = emptyList(),
    val vintfEntries: List<String> = emptyList(),
    val flattenedSourceSpec: String = "",
    val evidenceLevel: EvidenceLevel = EvidenceLevel.FAIT_VERIFIE
)

data class MechanismStageNode(
    val layer: MechanismLayer,
    val status: StageStatus,
    val componentsCount: Int,
    val componentNames: List<String>,
    val factualSummary: String,
    val rawProofs: List<String>
)

data class MechanismReconstruction(
    val outcome: ReconstructionOutcome,
    val summary: String,
    val causalExecutionPath: List<String>,
    val firstBreakDescription: String? = null,
    val firstBreakProof: String? = null,
    val stages: List<MechanismStageNode> = emptyList(),
    val verifiedFactsCount: Int = 0,
    val observedCorrelationsCount: Int = 0,
    val limitationsCount: Int = 0,
    val generatedAtIso: String = ""
)

data class ExportedFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val category: String
)

data class ExportSessionState(
    val sessionId: String = "",
    val sessionDirectoryPath: String = "",
    val zipFilePath: String = "",
    val isPrimarySdcardFod: Boolean = false,
    val pendingSyncToSdcard: Boolean = false,
    val exportedFiles: List<ExportedFileEntry> = emptyList(),
    val lastExportIso: String = "",
    val exportMessage: String = ""
)

data class ImportedFodSession(
    val sourceLabel: String,
    val sessionId: String,
    val manufacturerModel: String,
    val romType: RomType,
    val romLabel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val rootActive: Boolean,
    val outcome: ReconstructionOutcome,
    val outcomeLabel: String,
    val mechanismSummary: String,
    val causalExecutionPath: List<String>,
    val firstBreakDescription: String?,
    val firstBreakProof: String?,
    val flattenedHals: List<FlattenedHalArtifact>,
    val components: List<DiscoveredComponent>,
    val modifications: List<DetectedModification>,
    val properties: Map<String, String>,
    val sysfsNodes: Map<String, String>,
    val rcBlocks: List<String>,
    val permissionEntries: List<String>,
    val eventsCount: Int
)

enum class DiffSeverity(val label: String) {
    MISSING_IN_B("MANQUANT DANS B"),
    DIVERGENT("DIVERGENCE"),
    PRESENT_IN_BOTH("PRÉSENT DANS A & B"),
    EXTRA_IN_B("SPÉCIFIQUE À B")
}

data class ComparisonDiffItem(
    val id: String,
    val layerName: String,
    val elementName: String,
    val severity: DiffSeverity,
    val detailInA: String,
    val detailInB: String,
    val portingImpact: String
)

data class PortingActionItem(
    val priority: String,
    val layer: String,
    val title: String,
    val factualReason: String
)

data class FodComparisonReport(
    val sessionA: ImportedFodSession,
    val sessionB: ImportedFodSession,
    val mechanismASummary: String,
    val mechanismBSummary: String,
    val workingFactorsInA: List<String>,
    val findingsInB: List<String>,
    val diffs: List<ComparisonDiffItem>,
    val portingChecklist: List<PortingActionItem>,
    val missingCount: Int,
    val divergentCount: Int,
    val matchedCount: Int,
    val generatedAtIso: String
)
