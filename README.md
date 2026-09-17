# Sommeil

> 🤖 Vibecodé avec [Claude Code](https://claude.com/claude-code) — la quasi-totalité du code a été générée par IA.

Application Android qui affiche une année de données de santé sous forme de grille façon GitHub :
plus la nuit est longue (ou la journée active), plus la case est verte ; plus elle est courte, plus elle tire vers le rouge.

Les données viennent de [Health Connect](https://health.google/health-connect-android/) et restent sur le téléphone : aucune connexion réseau, aucun stockage externe.

## Fonctionnalités

- Grille annuelle pour quatre métriques — sommeil, pas, cœur au repos, poids — navigation par année, détail d'une journée au toucher.
- Sommeil : sessions Health Connect, stades « éveillé » déduits, chevauchements entre montre et téléphone fusionnés, nuit rattachée à la date du réveil.
- Pas : agrégation quotidienne Health Connect. Cœur au repos et poids : moyenne des relevés du jour.
- Statistiques par métrique : moyenne, 7 derniers jours, record, tendance sur 30 jours.
- **Activité et sommeil** : nuage de points et coefficient de corrélation entre les pas d'une journée
  et la nuit qui la suit, avec la comparaison « après 8 000 pas ou plus » contre « après une journée calme ».
- **Rappels** (optionnels) : rappel du soir quand la moyenne des 7 derniers jours passe sous l'objectif,
  et résumé du dimanche comparant la semaine à la précédente. Calculés sur le téléphone.
- **Widget** d'écran d'accueil : les dernières semaines de la métrique sélectionnée dans l'app.
- **Partage** : export de la grille de l'année en PNG, via le sélecteur de partage Android.
- Mode démo si Health Connect n'est pas disponible.

## Échelles de couleurs

| Métrique | ← moins bien | | | | mieux → |
| --- | --- | --- | --- | --- | --- |
| **Sommeil** | < 5h | 5-6h | 6-7h | 7-8h | 8h+ |
| **Pas** | < 3k | 3-6k | 6-8k | 8-10k | 10k+ |
| **Cœur au repos** | 70+ | 64-70 | 58-64 | 52-58 | < 52 |

Le poids n'a pas de « bon » côté : il reçoit un dégradé bleu neutre, calé sur les quintiles
de l'année affichée (du plus léger au plus lourd), avec les seuils réels en légende.

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

Au premier lancement, accorde à l'app l'accès aux données dans Health Connect. Sans l'autorisation
« historique », Health Connect ne restitue que les 30 jours précédant la première autorisation ;
sans « arrière-plan », le widget et les rappels se contentent des dernières données lues par l'app.

## Permissions

- `android.permission.health.READ_SLEEP`
- `android.permission.health.READ_STEPS`
- `android.permission.health.READ_RESTING_HEART_RATE`
- `android.permission.health.READ_WEIGHT`
- `android.permission.health.READ_HEALTH_DATA_HISTORY`
- `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`
- `android.permission.POST_NOTIFICATIONS` (rappels, demandée seulement si tu les actives)
- `android.permission.RECEIVE_BOOT_COMPLETED` (replacer les rappels après un redémarrage)

## Limites

L'APK publié en release est signé avec la clé de debug : suffisant pour une installation personnelle,
pas pour une publication sur le Play Store.

Les rappels utilisent des alarmes inexactes (`setAndAllowWhileIdle`) : ils tombent à quelques minutes
près, ce qui évite de réclamer l'autorisation « alarmes exactes ».
