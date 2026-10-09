# EterReward

Les récompenses du réseau. Pour l'instant : la **récompense quotidienne** (`/daily`). Plus tard : les récompenses de
vote. Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.10.0+** (`depend`, textes communs, cadre des menus, bus réseau, `Money`) : base, langues, menus (bouton Retour/Fermer), durées lisibles.
- **EterEconomy 2.2.1+** (`depend`, son API `EconomyApi`) pour les récompenses en argent.

## Récompense quotidienne

**Règles** : une récompense par jour et par joueur, **sur tout le réseau**. Une série de 7 jours : réclamer chaque jour
fait avancer d'un jour, **un jour manqué fait repartir au jour 1**, et après le jour 7 la série recommence. Le jour
change à **minuit dans `daily.time-zone`** (Europe/Paris), le même pour tout le monde.

**Données** (`DailyStore`, table `eterreward_daily`) : `uuid`, `day` (dernier jour réclamé, 1 à 7), `last_claim`
(date de cette réclamation en jour julien, `LocalDate#toEpochDay`). `DailyState` en déduit l'état du jour : déjà
réclamé, série qui continue (réclamé hier), ou retour au jour 1.

**Réclamer** (`DailyService#claim`) : la base enregistre **d'abord**, par une requête conditionnelle
(`UPDATE ... WHERE last_claim = <valeur lue>`, ou `INSERT IGNORE` la première fois) : deux clics ou deux serveurs au même
moment ne donnent qu'une récompense. Ensuite seulement, un tirage est choisi au hasard selon les poids et donné :
objets (ce qui ne rentre pas est **posé au sol** devant le joueur), commandes lancées par la console (`{player}`),
argent par EterEconomy en tâche de fond (source « EterReward · récompense du jour »). Son et particules, plus marqués le jour 7.

**Rappel** : 3 secondes après l'arrivée, si la récompense du jour attend, un message avec un bouton `/daily` (et un
avertissement si la série vient d'être perdue).

**Menu** (`DailyMenu`, 5 lignes) : tête du joueur (série, temps avant le prochain jour, en direct), les 7 jours
(réclamé, aujourd'hui qui brille, à venir ; la pile de l'icône = numéro du jour ; tirages possibles et leurs chances),
bouton Retour/Fermer (`menus.daily.back-command`).

**Configuration** : `daily.days.1` à `7`, chacun avec `icon` et une liste `loots` (`weight`, `money`, `items`
"MATIÈRE quantité", `commands`, `label`). Un tirage invalide est ignoré avec un avertissement dans la console.

## Commandes et permissions

| Commande | Permission | Par défaut |
|---|---|---|
| `/daily` (`reward`, `recompense`) | `eterreward.daily` | tous |

`eterreward.admin` regroupe tout.

## API (pour les autres plugins)

`fr.eternom.eterReward.api.RewardApi` (`RewardApi.get()`) : personne d'autre ne lit `eterreward_daily`.

- `streak(uuid)` : jours réclamés de la série en cours (0 si perdue), `claimedToday(uuid)` (bloquant) ;
- `openMenu(joueur)` : le menu `/daily`.
