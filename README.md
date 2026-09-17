# Sommeil

> 🤖 Vibecodé avec [Claude Code](https://claude.com/claude-code) — la quasi-totalité du code a été générée par IA.

Application Android qui affiche une année de sommeil et de pas sous forme de grille façon GitHub :
plus la nuit est longue (ou la journée active), plus la case est verte ; plus elle est courte, plus elle tire vers le rouge.

Les données viennent de [Health Connect](https://health.google/health-connect-android/) et restent sur le téléphone : aucune connexion réseau, aucun stockage externe.

## Fonctionnalités

- Grille annuelle sommeil / pas, navigation par année, détail d'une journée au toucher.
- Sommeil : sessions Health Connect, stades « éveillé » déduits, chevauchements entre montre et téléphone fusionnés, nuit rattachée à la date du réveil.
- Pas : agrégation quotidienne Health Connect.
- Statistiques : moyenne, 7 derniers jours, record, nuits < 6h / jours ≥ 10 000 pas.
- Mode démo si Health Connect n'est pas disponible.

## Échelles de couleurs

| Sommeil | < 5h | 5-6h | 6-7h | 7-8h | 8h+ |
| --- | --- | --- | --- | --- | --- |
| **Pas** | < 3k | 3-6k | 6-8k | 8-10k | 10k+ |

## Compiler

Nécessite un JDK 17+ et le SDK Android (plateforme 36). Indique le chemin du SDK dans `local.properties` :

```
sdk.dir=C:/chemin/vers/android-sdk
```

Puis :

```
./gradlew assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/`.

## Installer

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Au premier lancement, accorde à l'app l'accès au sommeil et aux pas dans Health Connect. Sans l'autorisation
« historique », Health Connect ne restitue que les 30 jours précédant la première autorisation.

## Permissions

- `android.permission.health.READ_SLEEP`
- `android.permission.health.READ_STEPS`
- `android.permission.health.READ_HEALTH_DATA_HISTORY`

## Limites

L'APK publié en release est signé avec la clé de debug : suffisant pour une installation personnelle,
pas pour une publication sur le Play Store.
