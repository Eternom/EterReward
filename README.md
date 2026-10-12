# EterReward

Les récompenses du réseau : la **récompense quotidienne** (`/daily`) et le **pass de saison** (`/pass`). Plus tard : les
récompenses de vote. Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.10.0+** (`depend`, textes communs, cadre des menus, bus réseau, `Money`) : base, langues, menus (bouton Retour/Fermer), durées lisibles.
- **EterEconomy 2.2.2+** (`depend`, son API `EconomyApi`) pour les récompenses en argent.
- Facultatifs (`softdepend`), chacun dans une classe à part chargée seulement s'il est là :
  - **EterMarket 1.5.0+** : son événement `JobQuestCompleteEvent` donne l'XP du pass des quêtes de métier ;
  - **EterResource** : son API `ResourceApi` pour les clés et le temps de monde ressource ;
  - **LuckPerms** : les permissions temporaires (récompenses, premium).

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

## Pass de saison

**Rotation** (`PassService#season`) : chaque mois (fuseau `daily.time-zone`), un des pass de `passes/` est tiré au hasard,
jamais celui du mois d'avant. Le premier serveur qui en a besoin l'écrit (`INSERT IGNORE` dans `eterreward_pass_seasons`),
les autres le lisent : le même pour tout le réseau. `/eterreward pass set <pass>` le force. Un pass = un fichier de
`passes/` (`PassDefinition`) : nom, icône, description, **bonus du mois** (`quest-job`/`quest-multiplier`,
`daily-multiplier`, `playtime-cap`) et 30 paliers ; les 8 fournis (`PassCatalog`) sont copiés au premier démarrage.

**XP** (`pass.*` de la config) : `xp-per-tier` par palier. Sources :
- **temps de jeu actif** (`PassActivity`) : chaque minute, les joueurs qui ont bougé, tourné la tête, agi ou parlé gagnent
  `playtime-xp-per-minute`, plafonné à `playtime-daily-cap` par jour (une seule requête qui ajoute et plafonne,
  `PassStore#addPlaytime`) : l'AFK ne mène pas au bout, il faut faire les quêtes ;
- **`/daily`** réclamé : `daily-xp` (dans la même tâche que la réclamation) ;
- **quêtes de métier** (EterMarket) : `quest-xp` selon la difficulté (bonus = la quête bonus du jour) ;
- `RewardApi#addPassXp` (autres plugins) et `/eterreward pass xp`.
Un palier atteint prévient le joueur (bouton `/pass`).

**Réclamer** (`PassMenu` : 6 lignes, 7 paliers par page ; gratuit en haut, paliers au milieu, premium en bas) : la base
enregistre d'abord (`INSERT IGNORE` dans `eterreward_pass_claims`, une seule fois même sur deux serveurs), puis on donne.
Argent, clés et permissions partent en tâche de fond juste après l'enregistrement (même si le joueur part entre les
deux) ; objets (au sol s'ils ne rentrent pas) et commandes sur le thread principal. Une récompense de clés ou de
permissions est refusée (« réclame-la ailleurs ») sur un serveur sans EterResource ou LuckPerms. Les récompenses
non réclamées à la fin du mois sont **perdues**.

**Récompenses** : `money`, `keys`, `resource-minutes`, `items`, `permissions` (`"eteressential.craft 7d"` : par
LuckPerms, une durée déjà en cours s'allonge ; **sa propre durée, même après la fin du pass** ; seulement des commandes
d'usage, jamais ce que le joueur garde comme des homes), `commands`, `label`.

**Premium** : permission `eterreward.pass.premium.<pass>` (ou `.*`), vérifiée au clic. `/eterreward pass premium
<joueur>` (staff ou console, ex : une boutique) la donne par LuckPerms **jusqu'à la fin du mois** exactement, même hors
ligne.

## Commandes et permissions

| Commande | Permission | Par défaut |
|---|---|---|
| `/daily` (`reward`, `recompense`) | `eterreward.daily` | tous |
| `/pass` (`battlepass`, `saison`) | `eterreward.pass` ; premium : `eterreward.pass.premium.<pass>` | tous / personne |
| `/eterreward pass premium <joueur>` | `set <pass>` | `xp <joueur> <montant>` (aussi depuis la console) | `eterreward.admin` | op |

`eterreward.admin` regroupe tout.

## API (pour les autres plugins)

`fr.eternom.eterReward.api.RewardApi` (`RewardApi.get()`) : personne d'autre ne lit les tables `eterreward_*`.

- `streak(uuid)` : jours réclamés de la série en cours (0 si perdue), `claimedToday(uuid)` (bloquant) ;
- `openMenu(joueur)` : le menu `/daily` ;
- `addPassXp(uuid, xp)` : XP du pass en cours (bloquant), `openPass(joueur)` : le menu `/pass`.

## Données

- `eterreward_daily` : `uuid`, `day` (dernier jour réclamé), `last_claim` (jour julien).
- `eterreward_pass_seasons` : `season` (`2026-10`), `pass`.
- `eterreward_pass_progress` : `uuid`, `season`, `xp`, `play_day` / `play_xp` (temps de jeu compté aujourd'hui, pour le plafond).
- `eterreward_pass_claims` : `uuid`, `season`, `tier`, `premium` (clé : les quatre).
