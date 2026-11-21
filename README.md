# FOD DETECTOR

Moteur universel hors ligne de détection, d'observation passive et de rétro-ingénierie factuelle du mécanisme **Fingerprint On Display (FOD / UDFPS / Side-FPS / Hybride)** sous Android, compatible **ROM Constructeur (Stock)**, **GSI (Treble)** et **ROM Custom**, avec prise en charge facultative des privilèges **ROOT**.

## Principes fondamentaux

- **100 % factuel et déterministe** : aucune architecture FOD prédéfinie n'est imposée, aucune hypothèse n'est inventée. Chaque découverte distingue explicitement :
  - `FAIT VÉRIFIÉ` (preuve brute directe horodatée)
  - `CORRÉLATION OBSERVÉE` (variation ou lien temporel mesuré)
  - `LIMITATION / NON OBSERVÉ` (périmètre restreint sans ROOT ou permission)
- **Non-bloquant** : l'absence de ROOT ou de permission de stockage n'interrompt jamais le moteur ; il poursuit avec les capacités accessibles et signale exactement ses limites.
- **Strictement passif (lecture seule)** : aucune modification du framework, du vendor, du kernel ou des partitions, aucune altération des configurations FOD et aucune extraction des données biométriques protégées.
- **Surveillance persistante en arrière-plan** : service de premier plan (*Foreground Service*) maintenant la collecte active lorsque vous basculez vers les Paramètres Android pour enregistrer une empreinte.

## Arborescence générée dans `/sdcard/FOD/`

Chaque session produit un dossier horodaté `/sdcard/FOD/session_<YYYYMMDD_HHMMSS>/` (ou dans le dossier de repli de l'application avec synchronisation manuelle si la permission stockage n'est pas encore accordée) :

- `report.json` : rapport maître consolidé
- `device_profile.json` : profil matériel, SoC, SELinux, classification ROM (Stock / GSI / Custom)
- `permissions_and_limits.json` : état Stockage, ROOT, Notifications et limitations actives
- `baseline_state.json` : instantané initial capturé avant l'enrôlement
- `events.json` : chronologie horodatée des événements et déclencheurs
- `components.json` : services HAL/Binder, bibliothèques, nœuds sysfs/proc, propriétés, overlays découverts
- `dependencies.json` : graphe des relations causales observées
- `patches_and_hooks.json` : modules Magisk/KernelSU/APatch, hooks LSPosed, scripts `init.*.rc`, shims GSI, overlays RRO
- `mechanism_mapping.json` : reconstruction finale par couches (succès ou première rupture démontrable)
- `evidence/` : preuves brutes (`getprop_fod.txt`, `dumpsys_biometric.txt`, `hal_and_services.txt`, `sysfs_snapshot.txt`, `logcat_fod.txt`, `overlays_and_modules.txt`, `accessible_resources.json`)

## Compilation GitHub Actions

Le workflow `.github/workflows/android-build.yml` exécute automatiquement sur `push` / `pull_request` :
1. Installation de JDK 21, Android SDK et Gradle `9.3.1`
2. Décodage de `debug.keystore.base64` vers `debug.keystore`
3. Exécution des tests unitaires (`gradle :app:testDebugUnitTest`)
4. Compilation de l'APK unique (`gradle :app:assembleDebug`) et publication de l'artefact `fod-detector-debug-apk`
