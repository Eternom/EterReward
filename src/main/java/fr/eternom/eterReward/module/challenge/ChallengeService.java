package fr.eternom.eterReward.module.challenge;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.api.ChallengeCompleteEvent;
import fr.eternom.eterReward.module.challenge.ChallengeDefinition.Kind;
import fr.eternom.eterReward.module.pass.ClanGoals;
import fr.eternom.eterReward.module.pass.PassService;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Défis du jour et de la semaine : les mêmes pour tout le réseau, tirés au hasard dans challenges.yml (le premier
 * serveur qui en a besoin les écrit en base). Chaque action qui compte (bloc naturel cassé, créature tuée, prise,
 * quête...) s'ajoute en mémoire, puis part en base toutes les 15 s et au départ du joueur (une requête relative par
 * défi). Un défi atteint est validé par une requête conditionnelle : récompense donnée une seule fois, même si deux
 * serveurs valident en même temps. Récompense : XP du pass et argent.
 */
public class ChallengeService {

    /** Période d'un défi : jour, semaine, ou mission de la semaine du pass (choisie selon le pass du mois). */
    public enum Period { DAILY, WEEKLY, MISSION, CLAN }

    /** Défis en cours d'une période, avec leur clé en base. */
    record Active(Period period, String key, List<ChallengeDefinition> challenges, ZonedDateTime end) {
    }

    private final JavaPlugin plugin;
    private final ChallengeStore store;
    private final Map<Period, List<ChallengeDefinition>> pools;
    private final Map<Period, Integer> counts;
    private final PassService pass;
    private final Messages messages;
    private final ZoneId zone;
    private final BackButton backButton;
    private final Map<Period, Active> current = new ConcurrentHashMap<>();
    /** La période d'avant : les actions faites juste avant minuit, enregistrées après, la valident encore. */
    private final Map<Period, Active> previous = new ConcurrentHashMap<>();
    /** Appelé en tâche de fond à chaque défi validé (classement du mois). */
    private Consumer<UUID> onComplete = uuid -> { };
    /** Avancée pas encore enregistrée : joueur -> "période|défi" -> quantité. */
    private final Map<UUID, Map<String, Integer>> pending = new ConcurrentHashMap<>();

    public ChallengeService(JavaPlugin plugin, ChallengeStore store, Map<Period, List<ChallengeDefinition>> pools,
                            Map<Period, Integer> counts, PassService pass, Messages messages, ZoneId zone, BackButton backButton) {
        this.plugin = plugin;
        this.store = store;
        this.pools = pools;
        this.counts = counts;
        this.pass = pass;
        this.messages = messages;
        this.zone = zone;
        this.backButton = backButton;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, 20 * 15, 20 * 15);
    }

    public void setOnComplete(Consumer<UUID> onComplete) {
        this.onComplete = onComplete;
    }

    // ---------- Périodes ----------

    /** Les défis en cours (jour ou semaine), tirés au premier besoin. Bloquant (base). */
    Active active(Period period) {
        String key = key(period);
        Active active = current.get(period);
        if (active != null && active.key().equals(key)) {
            return active;
        }
        List<ChallengeDefinition> pool = themed(period, pools.getOrDefault(period, List.of()));
        List<String> ids = store.period(key).orElseGet(() -> store.createPeriod(key, pick(pool, counts.getOrDefault(period, 3))));
        List<ChallengeDefinition> challenges = new ArrayList<>();
        for (String id : ids) {
            // Un défi absent de ce serveur (challenges.yml différent) est ignoré ici
            pool.stream().filter(challenge -> challenge.id().equals(id)).findFirst().ifPresent(challenges::add);
        }
        Active old = current.get(period);
        active = new Active(period, key, List.copyOf(challenges), end(period));
        current.put(period, active);
        if (old != null) {
            previous.put(period, old);
        }
        return active;
    }

    /** Missions : celles choisies par le pass du mois (s'il en choisit et qu'elles existent), sinon toutes. Bloquant. */
    private List<ChallengeDefinition> themed(Period period, List<ChallengeDefinition> pool) {
        if (period != Period.MISSION) {
            return pool;
        }
        List<String> chosen = pass.missionIds();
        List<ChallengeDefinition> themed = pool.stream().filter(challenge -> chosen.contains(challenge.id())).toList();
        return themed.isEmpty() ? pool : themed;
    }

    private static List<String> pick(List<ChallengeDefinition> pool, int count) {
        List<String> ids = new ArrayList<>(pool.stream().map(ChallengeDefinition::id).toList());
        Collections.shuffle(ids);
        return ids.subList(0, Math.min(count, ids.size()));
    }

    private String key(Period period) {
        LocalDate today = LocalDate.now(zone);
        return period == Period.DAILY
                ? "d:" + today.toEpochDay()
                : (period == Period.MISSION ? "m:" : period == Period.CLAN ? "c:" : "w:") + today.get(IsoFields.WEEK_BASED_YEAR) + "-" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private ZonedDateTime end(Period period) {
        LocalDate today = LocalDate.now(zone);
        LocalDate next = period == Period.DAILY ? today.plusDays(1) : today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return next.atStartOfDay(zone);
    }

    String timeLeft(Player viewer, Active active) {
        long seconds = Duration.between(ZonedDateTime.now(zone), active.end()).toSeconds();
        return EterLib.get().formatDuration(viewer, Math.max(0, seconds));
    }

    // ---------- Comptage ----------

    /**
     * Une action comptable (thread principal). blockOrEntity : la matière ou la créature (pour match). Les défis en
     * cours sont ceux déjà connus de ce serveur : au tout début d'une période, rien n'est compté avant que quelqu'un les
     * ait demandés (au plus 15 s).
     */
    void count(Player player, Kind kind, Predicate<ChallengeDefinition> matches) {
        for (Active active : current.values()) {
            if (!active.key().equals(key(active.period()))) {
                continue; // période finie, les nouveaux défis arrivent au prochain enregistrement
            }
            for (ChallengeDefinition challenge : active.challenges()) {
                if (challenge.kind() == kind && matches.test(challenge)) {
                    // compute est atomique avec le remove de flush : aucune action perdue entre les deux
                    String key = active.key() + "|" + challenge.id();
                    pending.compute(player.getUniqueId(), (uuid, counts) -> {
                        Map<String, Integer> map = counts == null ? new HashMap<>() : counts;
                        map.merge(key, 1, Integer::sum);
                        return map;
                    });
                }
            }
        }
    }

    /** Toutes les 15 s (tâche de fond) : enregistre, valide ce qui est atteint, et tire les défis d'une nouvelle période. */
    /** À l'arrêt du serveur, sur le thread principal : la progression en mémoire est enregistrée, rien ne se perd. */
    public void stop() {
        flushAll();
    }

    private void flushAll() {
        try {
            for (Period period : Period.values()) {
                active(period);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Défis : tirage impossible : " + e.getMessage());
        }
        for (UUID uuid : List.copyOf(pending.keySet())) {
            flush(uuid);
        }
    }

    /** Départ du joueur (thread principal) : son avancée part tout de suite. */
    void quit(UUID uuid) {
        Tasks.async(plugin, () -> flush(uuid), "Avancée des défis non enregistrée pour " + uuid);
    }

    private void flush(UUID uuid) {
        Map<String, Integer> counts = pending.remove(uuid);
        if (counts == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            try {
                store.add(uuid, parts[0], parts[1], entry.getValue());
                if (parts[0].startsWith(CLAN_PREFIX)) {
                    // Défi de clan : la part du joueur (ci-dessus) et le total de son clan, validé pour le clan entier
                    ClanGoals.Clan clan = clanOf(uuid);
                    if (clan != null) {
                        store.add(clanKey(clan.id()), parts[0], parts[1], entry.getValue());
                        checkClan(clan, parts[0], parts[1]);
                    }
                } else {
                    checkCompletion(uuid, parts[0], parts[1]);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Avancée du défi " + parts[1] + " perdue pour " + uuid + " : " + e.getMessage());
            }
        }
    }

    // ---------- Défi de clan de la semaine ----------

    private static final String CLAN_PREFIX = "c:";
    private static final long CLAN_CACHE_MILLIS = 5 * 60_000;
    /** Clan des joueurs (relu toutes les 5 min) : joueur -> {clan ou null, lu à}. */
    private final Map<UUID, Object[]> clans = new ConcurrentHashMap<>();

    /** Bloquant : le clan du joueur (EterClan), gardé 5 min ; null sans clan. */
    ClanGoals.Clan clanOf(UUID player) {
        Object[] known = clans.get(player);
        long now = System.currentTimeMillis();
        if (known != null && now - (long) known[1] < CLAN_CACHE_MILLIS) {
            return (ClanGoals.Clan) known[0];
        }
        ClanGoals.Clan clan = ClanGoals.clanOf(player);
        clans.put(player, new Object[]{clan, now});
        return clan;
    }

    /** L'avancée d'un clan est gardée sous un identifiant propre au clan (même table que celle des joueurs). */
    static UUID clanKey(long clanId) {
        return UUID.nameUUIDFromBytes(("eterreward-clan:" + clanId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Le clan a atteint l'objectif : validé une seule fois (même entre serveurs), puis chaque membre qui a aidé est récompensé. */
    private void checkClan(ClanGoals.Clan clan, String periodKey, String id) {
        Active active = current.get(Period.CLAN);
        Active before = previous.get(Period.CLAN);
        Active known = active != null && active.key().equals(periodKey) ? active
                : before != null && before.key().equals(periodKey) ? before : null;
        if (known == null) {
            return;
        }
        for (ChallengeDefinition challenge : known.challenges()) {
            if (challenge.id().equals(id) && store.complete(clanKey(clan.id()), periodKey, id, challenge.target())) {
                java.util.Set<UUID> members = new java.util.HashSet<>(clan.members());
                for (UUID helper : store.contributors(periodKey, id)) {
                    if (members.contains(helper)) {
                        reward(helper, challenge, Period.CLAN);
                    }
                }
            }
        }
    }

    /** Valide le défi s'il vient d'être atteint, et donne la récompense. Bloquant. */
    private void checkCompletion(UUID uuid, String periodKey, String id) {
        List<Active> known = new ArrayList<>(current.values());
        known.addAll(previous.values());
        for (Active active : known) {
            if (!active.key().equals(periodKey)) {
                continue;
            }
            for (ChallengeDefinition challenge : active.challenges()) {
                if (challenge.id().equals(id) && store.complete(uuid, periodKey, id, challenge.target())) {
                    reward(uuid, challenge, active.period());
                }
            }
        }
    }

    /** Tâche de fond, juste après la validation : argent et XP (même si le joueur est parti), puis message. */
    private void reward(UUID uuid, ChallengeDefinition challenge, Period period) {
        if (challenge.money() > 0) {
            EconomyApi.get().ifPresent(economy -> economy.deposit(uuid, challenge.money(), "EterReward · défis"));
        }
        try {
            onComplete.accept(uuid);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Défi non compté au classement : " + e.getMessage());
        }
        int xp = challenge.xp();
        try {
            xp = pass.addXp(uuid, pass.challengeXp(challenge.xp()),
                    period == Period.MISSION ? PassService.Source.MISSION : PassService.Source.CHALLENGE);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("XP du pass non donnée pour le défi " + challenge.id() + " : " + e.getMessage());
        }
        int shownXp = xp;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return;
            }
            messages.send(player, switch (period) {
                        case DAILY -> "challenges.done-daily";
                        case WEEKLY -> "challenges.done-weekly";
                        case MISSION -> "challenges.done-mission";
                        case CLAN -> "challenges.done-clan";
                    },
                    "challenge", messages.plain(player, "challenges.list." + challenge.id(), "target", String.valueOf(challenge.target())),
                    "xp", String.valueOf(shownXp), "money", Money.format(challenge.money()));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            Bukkit.getPluginManager().callEvent(new ChallengeCompleteEvent(player, challenge.id(), period != Period.DAILY));
        });
    }

    // ---------- Menu ----------

    /** Ce que montre le menu : défis en cours et avancée du joueur (base + pas encore enregistré). */
    /** clanProgress : défi -> {total du clan, validé} ; inClan : le joueur a un clan (sinon pas de défi de clan pour lui). */
    record View(Active daily, Active weekly, Active missions, Active clan, Map<String, int[]> progress,
                Map<String, int[]> clanProgress, boolean inClan) {
    }

    public void open(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            Active daily = active(Period.DAILY);
            Active weekly = active(Period.WEEKLY);
            Active missions = active(Period.MISSION);
            Active clan = active(Period.CLAN);
            ClanGoals.Clan mine = clanOf(uuid);
            Map<String, int[]> clanProgress = mine == null ? Map.of() : store.progress(clanKey(mine.id()), clan.key());
            Map<String, int[]> progress = new HashMap<>();
            store.progress(uuid, daily.key()).forEach((id, value) -> progress.put(daily.key() + "|" + id, value));
            store.progress(uuid, weekly.key()).forEach((id, value) -> progress.put(weekly.key() + "|" + id, value));
            store.progress(uuid, missions.key()).forEach((id, value) -> progress.put(missions.key() + "|" + id, value));
            store.progress(uuid, clan.key()).forEach((id, value) -> progress.put(clan.key() + "|" + id, value));
            Map<String, Integer> unsaved = new HashMap<>();
            pending.computeIfPresent(uuid, (key, counts) -> {
                unsaved.putAll(counts); // copie sous verrou : le thread principal continue d'ajouter
                return counts;
            });
            unsaved.forEach((key, amount) -> progress.merge(key, new int[]{amount, 0}, (a, b) -> new int[]{a[0] + b[0], a[1]}));
            return new View(daily, weekly, missions, clan, progress, clanProgress, mine != null);
        }, view -> player.openInventory(new ChallengeMenu(this, player, view).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

    JavaPlugin plugin() {
        return plugin;
    }
}
