# EterReward

Les récompenses du réseau : la **récompense quotidienne** (`/daily`), le **pass de saison** (`/pass`) et les **défis**
(`/challenges`). Plus tard : les récompenses de vote. Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.18.0+** (`depend`, activité des joueurs `Activity`, textes communs, cadre des menus, bus réseau, `Money`, `Numbers`) : base, langues, menus (bouton Retour/Fermer), durées lisibles.
- **EterEconomy 2.2.2+** (`depend`, son API `EconomyApi`) pour les récompenses en argent.
- Facultatifs (`softdepend`), chacun dans une classe à part chargée seulement s'il est là :
  - **EterJob 2.0.0+** : son événement `JobQuestCompleteEvent` donne l'XP du pass des quêtes de métier et compte les quêtes du mois ;
  - **EterResource** : son API `ResourceApi` pour les clés et le temps de monde ressource ;
  - **LuckPerms** : les permissions temporaires des récompenses (sans lui, elles attendent en colis) ;
  - **EterClan 1.7.0+** : objectifs de clan et premium offert à tout le clan ;
  - **EterSuccess** : l'XP du pass des succès débloqués ;
  - **EterNpc 1.0.0+** : le podium du classement général ;
  - **PlaceholderAPI** : les placeholders du classement général (hologramme, scoreboard).

## Récompense quotidienne

**Règles** : une récompense par jour et par joueur, **sur tout le réseau**. Une série de 7 jours : réclamer chaque jour
fait avancer d'un jour, **un jour manqué fait repartir au jour 1**, et après le jour 7 la série recommence. Le jour
change à **minuit dans `daily.time-zone`** (Europe/Paris), le même pour tout le monde.
Réclamable seulement après `daily.min-active-minutes` (15) minutes de jeu actif dans la journée (temps de jeu du pass,
`PassService#activeMinutesToday`) : un second compte connecté juste pour la récompense ne l'a pas.

**Données** (`DailyStore`, table `eterreward_daily`) : `uuid`, `day` (dernier jour réclamé, 1 à 7), `last_claim`
(date de cette réclamation en jour julien, `LocalDate#toEpochDay`). `DailyState` en déduit l'état du jour : déjà
réclamé, série qui continue (réclamé hier), ou retour au jour 1.

**Réclamer** (`DailyService#claim`) : la base enregistre **d'abord**, par une requête conditionnelle
(`UPDATE ... WHERE last_claim = <valeur lue>`, ou `INSERT IGNORE` la première fois) : deux clics ou deux serveurs au même
moment ne donnent qu'une récompense. Joueur parti avant de la recevoir : la réclamation est annulée (`DailyStore#undo`),
il la refera ; l'XP du pass n'est donnée qu'une fois la récompense remise (jamais deux fois). Ensuite seulement, un tirage est choisi au hasard selon les poids et donné :
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
jamais un des `no-repeat-months` derniers (s'il en reste). Il est tiré `draw-ahead-days` avant la fin du mois d'avant
(`nextSeason`, tâche de fond toutes les 10 min) : aperçu dans le menu les `preview-days` derniers jours. Le premier serveur
qui en a besoin l'écrit (`INSERT IGNORE` dans `eterreward_pass_seasons`), les autres le lisent : le même pour tout le
réseau. Au premier besoin du mois, un seul serveur le marque annoncé (`announced`) et l'annonce partout (`PassAnnouncer`,
NetworkBus `eterreward`, et Discord si `discord-webhook`), avec le bilan du mois d'avant au staff (joueurs, palier moyen,
pass finis, premium). `/eterreward pass set <pass>` le force **sur tout le réseau** (XP, paliers réclamés et premium
gardés) ; `/eterreward pass reload` relit `config.yml` et `passes/` partout.

Un pass = un fichier de `passes/` (`PassDefinition`) : nom, icône, description, **bonus du mois** (`quest-job` /
`quest-multiplier`, `daily-multiplier`, `playtime-cap`, `challenge-multiplier`, `vote-multiplier`), `missions` (missions de
la semaine propres au pass) et 30 paliers ; les 10 fournis (`PassCatalog`) sont copiés s'ils manquent. Au chargement, un
rapport par pass (récompenses gratuites et premium) et des avertissements (paliers vides, icône inconnue, commande sans
`{player}`). Pour retirer un pass : `enabled: false`.

**XP** (`XpCurve`, logique pure testée) : `xp-curve` (coût de chaque palier, la dernière valeur vaut pour la suite), sinon
`xp-per-tier`. Après le dernier palier, des **paliers bonus** répétables (`overflow` : un tous les `xp`, `max` au plus,
`money`/`keys`). Sources (`PassService.Source`, journal `/pass history`) :
- **temps de jeu actif** (`PassActivity`, activité d'EterLib) : `playtime-xp-per-minute` (× `world-xp` selon le monde), plafonné
  par jour (`playtime-daily-cap` ou le `playtime-cap` du pass, × `playtime-cap-days` selon le jour), avec **report** de
  `playtime-carry-over` du plafond non utilisé la veille (un jour au plus). Toutes les minutes du serveur en **une lecture
  et une écriture** (`PassStore#progress(List)`, `#addPlaytime(List)`) ;
- **`/daily`**, **quêtes** (EterJob, `quest-xp`), **défis et missions**, **votes**, **classements**, **succès**
  (EterSuccess, `success-xp-per-point`), **objectifs de clan** (`ClanGoals` : palier moyen des membres, une fois par mois et
  par objectif), `RewardApi#addPassXp`, `/eterreward pass xp <joueur> <montant> [source]` (boss, évènements...).
- **Rattrapage** (`catch-up`) : en retard de plus de `margin` paliers sur le rythme attendu, XP × `multiplier` (hors temps
  de jeu, staff, boutique, clan).
- **Évènements** (`/eterreward pass event <durée> [multiplicateur] [uncapped]`, table `pass_boosts`, tout le réseau) et
  **boosters** (permission `eterreward.pass.boost.<pourcentage>`, ex : une récompense temporaire).

**Annonces de palier** : `markNotified` (une requête conditionnelle) garantit **une seule annonce par niveau**, même avec
deux gains au même moment ou sur deux serveurs ; en grand (titre) aux paliers 10, 20, 30. Le palier est dans la sidebar
d'EterTab (étiquette `pass`).

**Réclamer** (`PassMenu`) : palier par palier ou **tout réclamer** (gratuit, premium, bonus) ; filtre « à réclamer
seulement » ; clic droit = détail dans le chat ; ouverture sur la page du premier palier à réclamer. La base enregistre
d'abord (`INSERT IGNORE`, avec le serveur et l'heure), puis on donne : la partie à distance (permissions d'abord, puis clés
et argent) en tâche de fond. Si LuckPerms refuse une permission, **la réclamation est annulée** (rien n'a été donné) ; ce qui
ne peut pas être donné sur ce serveur (EterResource ou LuckPerms absent) part **en colis**. Objets et commandes ensuite sur
le thread principal ; ce qui ne tient pas dans l'inventaire part en colis. Les récompenses du mois d'avant restent
réclamables `grace-days` jours (bouton dans le menu, rappel à l'arrivée).

**Boîte de colis** (`/pass box`, `ParcelMenu`, table `pass_box`) : ce qui n'a pas pu être donné (inventaire plein,
joueur parti, plugin absent), et l'insigne du premium. Un colis est retiré de la base avant d'être donné (une seule fois
sur tout le réseau). L'ancienne file `pass_pending` est vidée dans la boîte au démarrage.

**Récompenses** : `money`, `keys`, `resource-minutes`, `items` (donnés par piles complètes), `permissions`
(`"eteressential.craft 7d"` : par LuckPerms, une durée déjà en cours s'allonge ; **sa propre durée, même après la fin du
pass** ; seulement des commandes d'usage), `commands`, `label`.

**Premium** (`PassPremium`) : **par mois**, en base (`pass_premium`) ; changer le pass du mois ne le retire à personne.
- boutique, staff : `/eterreward pass premium <joueur> [acheteur] [paliers]` (cadeau si acheteur, paliers sautés en plus) ou
  `gift <joueur> <acheteur>` ; acheté dans les `premium.late-buy-days` derniers jours, le mois suivant est compris ;
- en jeu (`premium.price`, 0 : désactivé) : Maj+clic sur le bouton premium ; Maj+clic droit : le chef de clan l'offre
  aux membres qui ne l'ont pas (`clan-discount`, à partir de `clan-min-members` membres) ;
- permissions : `eterreward.pass.premium.always` (grade : tous les mois), `.<mois>` (`2026-11`), `.*`, et l'ancienne
  `.<pass>` (toujours reconnue).
Chaque premium en base donne l'**insigne du mois** (objet de collection, `premium.badge`) dans la boîte. Le joueur est
prévenu où qu'il soit, avec le nombre de récompenses premium déjà réclamables.

## Défis

**Tirage** (`ChallengeService#active`) : chaque jour (minuit) et chaque semaine (lundi minuit), fuseau `daily.time-zone`,
`daily-count` et `weekly-count` défis sont tirés au hasard dans `challenges.yml`, et `missions-count` **missions du pass** (section `missions`, filtrées par la liste `missions` du pass du mois ; période `m:<année>-<semaine>`, source d'XP « mission »), **les mêmes pour tout le réseau** : le
premier serveur qui en a besoin les écrit (`INSERT IGNORE` dans `eterreward_challenge_periods`, période `d:<jour julien>` ou
`w:<année>-<semaine>`).

**Comptage** (`ChallengeListener`, en survie) : `break` (blocs **naturels** seulement : un bloc posé par un joueur ne compte
pas, EterLib `PlacedBlocks`), `harvest` (cultures mûres), `kill` (tuées par le joueur ; `#monsters` = les `Enemy`), `fish`,
`quest` (EterJob). Chaque action s'ajoute en mémoire (`compute` atomique avec la vidange), puis part en base toutes les
15 s et au départ (une requête relative par défi). Un défi atteint est validé par une requête conditionnelle
(`done = FALSE AND progress >= target`) : récompense (XP du pass, argent « EterReward · défis ») une seule fois, même avec
plusieurs serveurs ; puis l'événement `ChallengeCompleteEvent`.

**Menu** `/challenges` (aussi depuis le menu du pass) : défis du jour et de la semaine, barre d'avancée, récompense, temps
restant. Les textes de chaque défi : `challenges.list.<id>` dans `lang/`.

## Classements

`/ranking` (`RankingService`) : 9 classements, et un bouton vers `/stats` (EterSuccess) s'il est là. **Du mois** (remis à zéro chaque mois : un nouveau joueur part à
égalité) : XP du pass (`eterreward_pass_progress`), défis réussis (`eterreward_challenge_monthly`), quêtes de métier
réussies (`eterreward_ranking_monthly`, comptées à chaque `JobQuestCompleteEvent`), votes (même table, voir Votes), temps de jeu actif
(même table, board `playtime`, une ligne par minute active, voir plus bas) et le
**général** (`GeneralRanking`) : dans chacun des cinq classements (paliers du pass au lieu de l'XP, premium ou non),
une note sur 100 selon la PLACE (1er sur N = 100, dernier = 100/N, absent = 0, ex aequo à la même place), puis la
moyenne des cinq notes, gardée en
dixièmes (875 = 87,5 points). La place plutôt que la valeur : aucun classement n'écrase les autres. **Clans du mois** : la source `clans` d'EterClan (score de son classement du mois, en dixièmes). Les classements du mois
de temps de jeu, quêtes et défis sont offerts aux autres plugins par EterLib (`MonthlySource`, `monthly_<id>`) : EterClan
s'en sert pour l'activité de ses membres. **Depuis
toujours** : points de succès (source `success` d'EterSuccess, lue par EterLib `RankingSource` : EterReward ne dépend pas
d'EterSuccess, qui dépend déjà de lui), fortune (`EconomyApi#richest`), clans par territoire (source `clans` d'EterClan :
la ligne affiche le nom du clan et une bannière ; un vrai classement de clans viendra plus tard, hors de la moyenne).
Les tops (10) sont recalculés toutes les 5 minutes en tâche de fond et gardés en mémoire ; le général est calculé en
entier (toutes les places, en mémoire) ; la place du joueur dans les autres est lue à l'ouverture d'un classement.

**Temps de jeu** (`PassActivity`) : chaque minute, un joueur qui a bougé, agi ou parlé gagne l'XP de temps de jeu du pass
(plafonnée par jour) ; s'il a aussi **tourné la tête ou parlé**, la minute compte au temps du mois (le temps total
de toujours est compté par EterSuccess, `/stats`, avec la même règle). Ce classement n'a pas de plafond : un courant d'eau ou un
wagonnet en boucle déplace le joueur sans le faire regarder, l'AFK n'y compte donc pas. Affichage en heures (`12 h 34`).

**Podium** (`Podium`, avec EterNpc) : les 5 premiers du général en PNJ (`eterreward:podium_<1-5>`), avec le skin, le nom
et le score du joueur, mis à jour à chaque calcul ; une place vide montre « ??? ». `/eterreward podium <1-5>` pose une
place là où est le staff (sur un lobby : pour tous les lobbys), `/eternpc remove` la retire.

**Placeholders** (`RankingPlaceholders`, avec PlaceholderAPI), lus en mémoire : `%eterreward_general_rank%` et
`%eterreward_general_score%` (celui qui regarde, `-` s'il n'est pas classé : pour un hologramme « ta place » au milieu
du podium), `%eterreward_general_top_<1-10>_name%` / `_score%`, `%eterreward_general_players%`,
`%eterreward_general_timeleft%`.

**Fin du mois** : le premier serveur qui voit le nouveau mois et qui a EterResource réserve les récompenses de chaque
classement de `ranking.rewarded` (le général par défaut, aussi quand la ligne manque dans une ancienne config ; `INSERT IGNORE` dans `eterreward_ranking_rewards`) et donne au
top 3 du mois fini `ranking.rewards` (argent, clés de ressource, XP du pass du nouveau mois) ; chaque gagnant est
prévenu où qu'il soit, et les gagnants sont annoncés sur tout le réseau (bus `eterreward`, message `ranking-winners`).

## Votes

`eterreward vote <joueur>` (`VoteService`) : un vote validé sur un site de vote. C'est le plugin Vote du site (Azuriom)
qui la lance, par AzLink, comme **seule récompense** de chaque site, « joueur en ligne requis » décoché. Le joueur peut
être hors ligne ou sur un autre serveur : il est retrouvé dans l'annuaire d'EterLib et tout passe par la base (argent
par EterEconomy, XP du pass, clés par EterResource) ; il n'y a donc pas d'objets dans les récompenses de vote.

- **Série** (`VoteStore`, table `eterreward_votes`) : jours de vote d'affilée, dans le fuseau de `daily.time-zone`. Le
  premier vote du jour la fait avancer (+1 si le dernier vote date d'hier, sinon 1) ; la condition est dans la requête,
  deux votes au même instant ne comptent qu'une fois la journée.
- **Récompense** (`VoteRewards`, `votes.loots`) : UN tirage par vote, selon les poids (argent, XP du pass ou clé), et le
  bonus `votes.streak.<jours>` au premier vote du jour qui atteint ce nombre de jours (3, 7, 14 par défaut).
- **Classement** : chaque vote compte au classement « votes » du mois (`eterreward_ranking_monthly`, board `votes`) et
  au général (4e critère). `ranking.rewarded` récompense par défaut le général et les votes.
- **Messages** : le joueur est prévenu dans sa langue là où il est (bus `eterreward-votes`, message `received` avec le
  détail, rédigé par son serveur), et un merci est annoncé sur tout le réseau (`votes.announce`, message `thanks`).
- **Joueur absent** (`VoteStore`, table `eterreward_vote_away`) : connecté nulle part au moment du vote (annuaire
  d'EterLib), il reçoit quand même tout en base, et le résumé de ses votes est gardé : à sa prochaine arrivée sur un
  serveur, « pendant ton absence, tes votes (n) t'ont rapporté… » (`VoteListener`, 4 s après l'arrivée). Une **clé** tirée
  sur un serveur sans EterResource n'est plus perdue : elle attend la prochaine arrivée du joueur sur un serveur qui l'a.

## Commandes et permissions

| Commande | Permission | Par défaut |
|---|---|---|
| `/daily` (`reward`, `recompense`) | `eterreward.daily` | tous |
| `/pass` (`battlepass`, `saison`) [`colis` \| `historique` \| `premium`] | `eterreward.pass` ; premium : `eterreward.pass.premium.always` (ou `.<mois>`, `.*`) ; booster : `eterreward.pass.boost.<pourcentage>` | tous / personne |
| `/challenges` (`challenges`, `defi`) | `eterreward.challenges` | tous |
| `/ranking` (`ranking`, `top`) | `eterreward.ranking` | tous |
| `/eterreward pass premium <joueur> [acheteur] [paliers]` · `gift` · `skip` · `set <pass>` · `reload` · `xp <joueur> <montant> [source]` · `setxp` · `info` · `reset` · `unclaim <joueur> <palier> [premium]` · `event <durée> [multiplicateur] [uncapped] | stop` (aussi depuis la console) ; `/eterreward podium <1-5>` ; `/eterreward vote <joueur>` (console, lancée par le site) | `eterreward.admin` | op |

`eterreward.admin` regroupe tout.

## API (pour les autres plugins)

`fr.eternom.eterReward.api.RewardApi` (`RewardApi.get()`) : personne d'autre ne lit les tables `eterreward_*`.

- `streak(uuid)` : jours réclamés de la série en cours (0 si perdue), `claimedToday(uuid)` (bloquant) ;
- `openMenu(joueur)` : le menu `/daily` ;
- `addPassXp(uuid, xp)` : XP du pass en cours, `passTier(uuid)` : palier atteint (bloquants) ; `openPass(joueur)` : le menu `/pass` ;
- événement `DailyClaimEvent` (joueur, jour de la série, 7 = série complète) à chaque `/daily` réclamé ;
- `openChallenges(joueur)` : le menu `/challenges` ; événement `ChallengeCompleteEvent` (joueur, défi, semaine ou jour) à chaque
  défi validé.

## Données

- `eterreward_daily` : `uuid`, `day` (dernier jour réclamé), `last_claim` (jour julien).
- `eterreward_pass_seasons` : `season` (`2026-10`), `pass`, `announced`.
- `eterreward_pass_progress` : `uuid`, `season`, `xp`, `play_day` / `play_xp` / `play_cap` (temps de jeu du dernier jour compté et son plafond, report compris), `notified` (dernier niveau annoncé).
- `eterreward_pass_claims` : `uuid`, `season`, `tier` (31 et plus : paliers bonus), `premium` (clé : les quatre), `server`, `claimed_at`.
- `eterreward_pass_premium` : `uuid`, `season`, `source` (`shop`, `gift:<acheteur>`, `game`, `clan:<chef>`), `given_at`.
- `eterreward_pass_box` : colis (`id`, `uuid`, `season`, `label`, `money`, `resource_keys`, `resource_minutes`, `items`, `permissions`, `commands`, `special`).
- `eterreward_pass_xp_log` : `uuid`, `hour`, `source`, `amount` (gardé 7 jours).
- `eterreward_pass_boosts` : évènements (`until`, `multiplier`, `uncapped`, `label`).
- `eterreward_pass_clan_goals` : `clan`, `season`, `step` (objectifs de clan atteints).
- `eterreward_challenge_periods` : `period`, `challenges` (identifiants tirés, séparés par des virgules).
- `eterreward_challenge_progress` : `uuid`, `period`, `challenge`, `progress`, `done`.
- `eterreward_challenge_monthly` : `uuid`, `season`, `completed` (défis réussis du mois, pour le classement).
- `eterreward_ranking_monthly` : `uuid`, `season`, `board`, `amount` (autres valeurs du mois : quêtes de métier, votes, minutes de jeu).
- `eterreward_ranking_rewards` : `season`, `board`, `given_at` (récompenses de fin de mois déjà données).
- `eterreward_votes` : `uuid`, `streak` (jours de vote d'affilée), `last_day` (jour julien du dernier vote).
