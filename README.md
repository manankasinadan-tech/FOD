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

Chaque session produit un dossier horodaté `/sdcard/FOD/session_<YYYYMMDD_HHMMSS>/` (ou dans le dossier de repli de l'application avec synchronisation manuelle si la permission stockage n'est pas encore accordée), limité à **3 fichiers JSON maximum** + **1 seul fichier TXT** dans `evidence/`, accompagnés d'une archive **`.zip`** prête à partager/importer :

1. `1_overview_and_mechanism.json` : profil matériel/ROM (Stock / GSI / Custom), état des permissions, instantané initial (*baseline*) et reconstruction causale complète (succès ou première rupture démontrable).
2. `2_hal_components_and_porting.json` : HALs mis à plat (pseudo-source avec dépendances ELF64 `DT_NEEDED`, symboles exportés, blocs `.rc`, permissions XML, manifestes VINTF, contextes SELinux), composants, dépendances, nœuds `sysfs`, propriétés et patches/overlays.
3. `3_events_and_timeline.json` : chronologie horodatée des événements et inventaire des ressources accessibles.
4. `evidence/all_evidence.txt` : fichier texte unique consolidant toutes les preuves brutes (`getprop`, `dumpsys`, HAL mis à plat, `.rc`, permissions XML, VINTF, `sysfs`, `logcat`, modules/overlays).
5. `session_<YYYYMMDD_HHMMSS>.zip` : archive ZIP exportable (y compris via *Enregistrer sous...*) et réimportable.

## Onglet Comparateur (Stock vs GSI)

L'onglet **Comparateur** permet d'importer deux archives ZIP (Source A : système où le FOD fonctionne ; Source B : GSI ou ROM où le FOD échoue) pour comparer couche par couche (HAL AIDL/HIDL, bibliothèques `.so` et `DT_NEEDED`, fichiers `.rc`, permissions XML, propriétés FOD, nœuds `sysfs` / DRM / tactile) et dresser la liste factuelle de ce qui manque à B pour réussir le portage.

## Compilation GitHub Actions

Le workflow `.github/workflows/android-build.yml` exécute automatiquement sur `push` / `pull_request` :
1. Installation de JDK 21, Android SDK et Gradle `9.3.1`
2. Décodage de `debug.keystore.base64` vers `debug.keystore`
3. Exécution des tests unitaires (`gradle :app:testDebugUnitTest`)
4. Compilation de l'APK unique (`gradle :app:assembleDebug`) et publication de l'artefact `fod-detector-debug-apk`
