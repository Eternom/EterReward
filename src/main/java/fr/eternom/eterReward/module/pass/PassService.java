package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.module.pass.PassDefinition.Reward;
import fr.eternom.eterReward.module.pass.PassDefinition.Tier;
import fr.eternom.eterReward.module.pass.PassStore.Progress;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Le pass de saison : chaque mois, un des pass de passes/ (tiré quelques jours avant, jamais un des derniers mois), le
 * même pour tout le réseau. On gagne de l'XP en jouant (temps actif, plafonné par jour, avec report), avec /daily, les
 * quêtes, les défis et missions, les votes, les succès, les objectifs de clan ; chaque palier débloque une récompense
 * gratuite, et une premium pour qui a le premium du mois. Au-delà du dernier palier : des paliers bonus répétables.
 * Les récompenses du mois d'avant restent réclamables quelques jours (pass.grace-days).
 *
 * Réclamer : la base enregistre d'abord (INSERT IGNORE, une seule fois même sur deux serveurs), puis on donne : la partie
 * à distance en tâche de fond (si une permission ne peut pas être donnée, la réclamation est annulée), le reste sur le
 * thread principal. Ce qui ne peut pas être donné (inventaire plein, joueur parti, plugin absent ici) part en colis
 * dans la boîte (/pass box).
 */
public class PassService {

    public static final String PERMISSION = "eterreward.pass";
    public static final String PREMIUM_PERMISSION = "eterreward.pass.premium.";
    /** Premium de tous les mois tant que la permission est là (ex : grade Légende). */
    public static final String PREMIUM_ALWAYS = PREMIUM_PERMISSION + "always";
    /** eterreward.pass.boost.<pourcentage> : XP de pass en plus (booster, récompense). */
    public static final String BOOST_PERMISSION = "eterreward.pass.boost.";
    static final DateTimeFormatter SEASON_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    /** Paliers « clés » : annonce en grand. */
    private static final int KEY_TIER_EVERY = 10;

    /** D'où vient l'XP (journal /pass history). catchUp : compte pour le rattrapage ; boosted : évènements et boosters. */
    public enum Source {
        PLAYTIME(false, true), DAILY(true, true), QUEST(true, true), CHALLENGE(true, true), MISSION(true, true),
        VOTE(true, true), RANKING(false, false), SUCCESS(true, true), CLAN(false, false), EVENT(true, true),
        STAFF(false, false), SHOP(false, false), API(true, true);

        final boolean catchUp;
        final boolean boosted;

        Source(boolean catchUp, boolean boosted) {
            this.catchUp = catchUp;
            this.boosted = boosted;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Une saison : le mois ("2026-10"), son pass, son début et sa fin. */
    public record Season(String key, PassDefinition pass, ZonedDateTime start, ZonedDateTime end) {
    }

    /** Quelle saison montrer : celle du mois, celle d'avant (délai de grâce), celle d'après (aperçu). */
    enum Which { CURRENT, PREVIOUS, NEXT }

    /** Ce que montre le menu d'un joueur. */
    record View(Which which, Season season, int xp, Set<String> claimed, boolean premium, int playedToday, int capToday,
                int parcels, int previousClaimable, boolean preview, double catchUp, int boost, double event) {

        boolean readOnly() {
            return which == Which.NEXT;
        }
    }

    /** Un palier d'une ligne à réclamer (tier > nombre de paliers : palier bonus, ligne gratuite). */
    record Line(int tier, boolean premium) {
    }

    private final JavaPlugin plugin;
    private final PassStore store;
    private final int tierCount;
    private volatile Map<String, PassDefinition> passes;
    private volatile PassSettings settings;
    private final Messages messages;
    private final ZoneId zone;
    private final BackButton backButton;
    private final PassRewards rewards;
    private final NetworkBus bus;
    private final PassPremium premium;
    private final PassAnnouncer announcer;
    private volatile Season current;
    private volatile Season next;
    private volatile Season previous;
    /** Boosters des joueurs de ce serveur (pourcentage), relus chaque minute sur le thread principal. */
    private final Map<UUID, Integer> boosts = new ConcurrentHashMap<>();
    private volatile List<PassStore.Boost> events = List.of();
    /** Le menu /challenges, pour le bouton du menu du pass (null tant qu'il n'est pas branché). */
    private Consumer<Player> challengesMenu;

    public PassService(JavaPlugin plugin, PassStore store, int tierCount, Messages messages, ZoneId zone, BackButton backButton) {
        this.plugin = plugin;
        this.store = store;
        this.tierCount = tierCount;
        this.messages = messages;
        this.zone = zone;
        this.backButton = backButton;
        this.rewards = new PassRewards(plugin, messages);
        this.bus = EterLib.get().network(plugin, "eterreward", messages);
        this.passes = PassCatalog.load(plugin, tierCount);
        this.settings = PassSettings.load(plugin.getConfig(), tierCount, plugin.getLogger());
        this.premium = new PassPremium(this);
        this.announcer = new PassAnnouncer(this);
        bus.on("season", data -> resetSeasons());
        bus.on("reload", data -> reloadLocal());
        bus.on("events", data -> Tasks.async(plugin, this::refreshEvents, "Évènements du pass non relus"));
        announcer.listen(bus);
    }

    /** Au démarrage (tâche de fond) : saisons passées marquées annoncées, ancienne file d'attente en colis, évènements. */
    public void start() {
        // Tout de suite (démarrage) : sans ça, un redémarrage en cours de mois annoncerait le pass déjà en cours
        LocalDate today = LocalDate.now(zone);
        YearMonth month = YearMonth.from(today);
        store.markOldAnnounced((today.getDayOfMonth() == 1 ? month : month.plusMonths(1)).format(SEASON_FORMAT));
        Tasks.async(plugin, () -> {
            for (PassStore.Pending pending : store.takeAllPending()) {
                PassDefinition pass = passes.get(pending.pass());
                if (pass == null || pending.tier() < 1 || pending.tier() > pass.tiers().size()) {
                    continue;
                }
                Tier tier = pass.tiers().get(pending.tier() - 1);
                Reward reward = pending.premium() ? tier.premium() : tier.free();
                if (reward != null) {
                    store.addParcel(pending.player(), Parcel.of("", label(pending.tier(), pending.premium()), reward).localPart());
                }
            }
            refreshEvents();
        }, "Démarrage du pass");
        // Évènements relus et journal purgé de temps en temps
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                refreshEvents();
                store.purgeLog();
                if (enabled()) {
                    nextSeason(); // le pass du mois suivant est tiré quelques jours avant (annonce, aperçu)
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Entretien du pass : " + e.getMessage());
            }
        }, 20 * 60 * 10, 20 * 60 * 10);
    }

    public void setChallengesMenu(Consumer<Player> challengesMenu) {
        this.challengesMenu = challengesMenu;
    }

    void openChallenges(Player player) {
        if (challengesMenu != null) {
            challengesMenu.accept(player);
        }
    }

    public boolean enabled() {
        return !passes.isEmpty();
    }

    // ---------- Saisons ----------

    /** La saison du mois, tirée au premier besoin (ou quelques jours avant). Bloquant (base). */
    public Season season() {
        YearMonth month = YearMonth.now(zone);
        String key = month.format(SEASON_FORMAT);
        Season season = current;
        if (season != null && season.key().equals(key)) {
            return season;
        }
        String id = store.season(key).orElseGet(() -> store.createSeason(key, pick(month)));
        season = build(month, id);
        current = season;
        if (store.markAnnounced(key)) {
            announcer.announce(season);
        }
        return season;
    }

    /** Le pass du mois suivant, tiré pass.draw-ahead-days avant la fin du mois ; null avant. Bloquant. */
    Season nextSeason() {
        Season now = season();
        if (Duration.between(ZonedDateTime.now(zone), now.end()).toDays() >= settings.drawAheadDays()) {
            return null;
        }
        YearMonth month = YearMonth.now(zone).plusMonths(1);
        String key = month.format(SEASON_FORMAT);
        Season season = next;
        if (season != null && season.key().equals(key)) {
            return season;
        }
        String id = store.season(key).orElseGet(() -> store.createSeason(key, pick(month)));
        season = build(month, id);
        next = season;
        return season;
    }

    /** Aperçu du pass suivant : dans les pass.preview-days derniers jours. Bloquant. */
    Season previewSeason() {
        Season now = season();
        if (Duration.between(ZonedDateTime.now(zone), now.end()).toDays() >= settings.previewDays()) {
            return null;
        }
        return nextSeason();
    }

    /** Le mois d'avant, tant que ses récompenses sont réclamables (pass.grace-days) ; null sinon. Bloquant. */
    Season previousSeason() {
        YearMonth month = YearMonth.now(zone).minusMonths(1);
        ZonedDateTime graceEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).plusDays(settings.graceDays());
        if (settings.graceDays() <= 0 || !ZonedDateTime.now(zone).isBefore(graceEnd)) {
            return null;
        }
        String key = month.format(SEASON_FORMAT);
        Season season = previous;
        if (season != null && season.key().equals(key)) {
            return season;
        }
        String id = store.season(key).orElse(null);
        if (id == null || !passes.containsKey(id)) {
            return null;
        }
        season = build(month, id);
        previous = season;
        return season;
    }

    /** La saison dont les récompenses sont réclamables avec cette clé (en cours, ou d'avant dans le délai) ; null sinon. */
    Season claimable(String key) {
        Season now = season();
        if (now.key().equals(key)) {
            return now;
        }
        Season before = previousSeason();
        return before != null && before.key().equals(key) ? before : null;
    }

    private Season build(YearMonth month, String id) {
        PassDefinition pass = passes.getOrDefault(id, passes.values().iterator().next());
        return new Season(month.format(SEASON_FORMAT), pass, month.atDay(1).atStartOfDay(zone), month.plusMonths(1).atDay(1).atStartOfDay(zone));
    }

    /** Au hasard parmi les pass, sauf ceux des pass.no-repeat-months derniers mois (s'il en reste). */
    private String pick(YearMonth month) {
        List<String> choices = new ArrayList<>(passes.keySet());
        for (int back = settings.noRepeat(); back >= 1; back--) {
            List<String> recent = new ArrayList<>();
            for (int i = 1; i <= back; i++) {
                store.season(month.minusMonths(i).format(SEASON_FORMAT)).ifPresent(recent::add);
            }
            List<String> left = new ArrayList<>(choices);
            left.removeAll(recent);
            if (!left.isEmpty()) {
                choices = left;
                break;
            }
        }
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    /** Staff : force le pass de ce mois, sur tout le réseau. XP, paliers réclamés et premium restent. Bloquant. */
    public boolean force(String id) {
        if (!passes.containsKey(id)) {
            return false;
        }
        store.setSeason(YearMonth.now(zone).format(SEASON_FORMAT), id);
        resetSeasons();
        Bukkit.getScheduler().runTask(plugin, () -> bus.publish("season", new com.google.gson.JsonObject()));
        return true;
    }

    private void resetSeasons() {
        current = null;
        next = null;
        previous = null;
    }

    /** Relit config.yml et passes/ ici et sur tous les serveurs. Thread principal. */
    public void reload() {
        reloadLocal();
        bus.publish("reload", new com.google.gson.JsonObject());
    }

    private void reloadLocal() {
        plugin.reloadConfig();
        passes = PassCatalog.load(plugin, tierCount);
        settings = PassSettings.load(plugin.getConfig(), tierCount, plugin.getLogger());
        resetSeasons();
    }

    public Set<String> passIds() {
        return passes.keySet();
    }

    PassDefinition passById(String id) {
        return passes.get(id);
    }

    /** Missions de la semaine du pass en cours : les identifiants choisis par son fichier (vide : toutes). Bloquant. */
    public List<String> missionIds() {
        return enabled() ? season().pass().missions() : List.of();
    }

    // ---------- XP ----------

    XpCurve curve() {
        return settings.curve();
    }

    int tier(int xp) {
        return curve().tier(xp);
    }

    /** Palier atteint (0 sans pass). Bloquant (base). */
    public int tierOf(UUID player) {
        if (!enabled()) {
            return 0;
        }
        return tier(store.xp(player, season().key()));
    }

    /** Palier atteint avec cette XP (classement général). */
    public long tierFromXp(long xp) {
        return enabled() ? curve().tier((int) Math.min(Integer.MAX_VALUE, xp)) : 0;
    }

    /** Le plafond d'XP de temps de jeu du jour, avant report : celui du pass ou de la config, multiplié selon le jour. */
    int baseCap(Season season) {
        int cap = season.pass().bonus().playtimeCap() > 0 ? season.pass().bonus().playtimeCap() : settings.playtimeCap();
        return (int) Math.round(cap * settings.capDays().getOrDefault(LocalDate.now(zone).getDayOfWeek(), 1.0));
    }

    /** Une minute active d'un joueur de ce serveur, avec son XP de base (monde compris). */
    record Minute(UUID player, int amount) {
    }

    /**
     * Les minutes actives du serveur (tâche de fond) : une lecture et une écriture pour tout le monde, puis les annonces
     * de palier pour ceux qui en passent un.
     */
    void playtime(List<Minute> minutes) {
        if (minutes.isEmpty()) {
            return;
        }
        Season season = season();
        long today = LocalDate.now(zone).toEpochDay();
        int baseCap = baseCap(season);
        boolean uncapped = events().stream().anyMatch(PassStore.Boost::uncapped);
        double event = eventMultiplier();
        Map<UUID, Progress> rows = store.progress(minutes.stream().map(Minute::player).toList(), season.key());
        List<PassStore.PlaytimeGain> gains = new ArrayList<>();
        Map<UUID, Integer> gained = new HashMap<>();
        for (Minute minute : minutes) {
            Progress row = rows.getOrDefault(minute.player(), Progress.NONE);
            int cap = XpCurve.capToday(baseCap, today, row.playDay(), row.playXp(), row.playCap(), settings.carryOver());
            int used = row.playDay() == today ? row.playXp() : 0;
            int amount = (int) Math.round(minute.amount() * event * (1 + boosts.getOrDefault(minute.player(), 0) / 100.0));
            int gain = uncapped ? amount : Math.clamp(cap - used, 0, amount);
            if (gain <= 0 && row.playDay() == today) {
                continue; // plafond atteint : rien à écrire
            }
            gains.add(new PassStore.PlaytimeGain(minute.player(), gain, today, used + gain, cap));
            if (gain > 0) {
                gained.put(minute.player(), gain);
            }
        }
        store.addPlaytime(season.key(), gains);
        gained.entrySet().stream().collect(Collectors.groupingBy(Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())))
                .forEach((amount, players) -> store.log(players, Source.PLAYTIME.id(), amount));
        gained.forEach((player, gain) -> {
            Progress before = rows.getOrDefault(player, Progress.NONE);
            levelUp(player, season, before, before.xp() + gain, Source.PLAYTIME);
        });
    }

    /** XP de temps de jeu gagnée aujourd'hui ; et le plafond du jour (report compris). Bloquant. */
    int[] playedToday(UUID player, Season season) {
        Progress row = store.progress(player, season.key());
        long today = LocalDate.now(zone).toEpochDay();
        int cap = XpCurve.capToday(baseCap(season), today, row.playDay(), row.playXp(), row.playCap(), settings.carryOver());
        return new int[]{row.playDay() == today ? row.playXp() : 0, cap};
    }

    /** Minutes de jeu actif aujourd'hui (temps de jeu du pass) ; sans pass, pas de limite (Integer.MAX_VALUE). Bloquant. */
    public int activeMinutesToday(UUID player) {
        if (!enabled()) {
            return Integer.MAX_VALUE;
        }
        return playedToday(player, season())[0] / Math.max(1, settings.playtimeXpPerMinute());
    }

    /** /daily réclamé (tâche de fond de DailyService). Bloquant. */
    public void dailyClaimed(UUID player) {
        if (enabled()) {
            Season season = season();
            addXp(player, season, (int) Math.round(settings.dailyXp() * season.pass().bonus().dailyMultiplier()), Source.DAILY);
        }
    }

    /** Quête de métier validée (thread principal). tier : easy, normal, hard ou bonus. */
    void questCompleted(Player player, String job, String tier) {
        if (!enabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> {
            Season season = season();
            addXp(uuid, season, (int) Math.round(settings.questXp(tier) * season.pass().questMultiplier(job)), Source.QUEST);
        }, "XP du pass non donnée à " + player.getName());
    }

    /** Succès débloqué (thread principal) : XP selon ses points. */
    void successUnlocked(Player player, int points) {
        int xp = (int) Math.round(points * settings.successXp());
        if (enabled() && xp > 0) {
            UUID uuid = player.getUniqueId();
            Tasks.async(plugin, () -> addXp(uuid, season(), xp, Source.SUCCESS), "XP du pass non donnée pour un succès");
        }
    }

    /** XP de pass d'un défi réussi, avec le bonus du mois. Bloquant. */
    public int challengeXp(int base) {
        return enabled() ? (int) Math.round(base * season().pass().bonus().challengeMultiplier()) : base;
    }

    /** XP de pass d'un vote, avec le bonus du mois. Bloquant. */
    public int voteXp(int base) {
        return enabled() ? (int) Math.round(base * season().pass().bonus().voteMultiplier()) : base;
    }

    /** XP donnée par un autre plugin (API). Bloquant. */
    public void addXp(UUID player, int amount) {
        addXp(player, amount, Source.API);
    }

    /** XP d'une source (défi, vote, classement, staff...). Renvoie l'XP réellement gagnée (bonus compris). Bloquant. */
    public int addXp(UUID player, int amount, Source source) {
        return enabled() ? addXp(player, season(), amount, source) : 0;
    }

    int addXp(UUID player, Season season, int amount, Source source) {
        if (amount <= 0) {
            return 0;
        }
        Progress before = store.progress(player, season.key());
        double multiplier = 1;
        if (source.boosted) {
            multiplier *= eventMultiplier() * (1 + boosts.getOrDefault(player, 0) / 100.0);
        }
        if (source.catchUp) {
            multiplier *= catchUp(tier(before.xp()));
        }
        int gain = (int) Math.round(amount * multiplier);
        store.addXp(player, season.key(), gain);
        store.log(player, source.id(), gain);
        levelUp(player, season, before, before.xp() + gain, source);
        return gain;
    }

    /** Fixe l'XP d'un joueur (staff). Bloquant. */
    void setXp(UUID player, int xp) {
        store.setXp(player, season().key(), Math.max(0, xp));
    }

    /** Multiplicateur de rattrapage pour ce palier, aujourd'hui (1 : pas de rattrapage). */
    double catchUp(int tier) {
        LocalDate today = LocalDate.now(zone);
        return XpCurve.catchUp(tier, today.getDayOfMonth(), today.lengthOfMonth(), settings.catchUpEnd(), settings.catchUpMargin(),
                settings.catchUpMultiplier());
    }

    // ---------- Évènements et boosters ----------

    List<PassStore.Boost> events() {
        long now = System.currentTimeMillis();
        return events.stream().filter(boost -> boost.until() > now).toList();
    }

    double eventMultiplier() {
        return events().stream().mapToDouble(PassStore.Boost::multiplier).max().orElse(1);
    }

    /** Relit les évènements en cours. Bloquant. */
    void refreshEvents() {
        events = store.boosts();
    }

    /** Staff : un évènement (XP multipliée, temps de jeu sans plafond), sur tout le réseau. Bloquant. */
    void startEvent(Duration duration, double multiplier, boolean uncapped, String label) {
        store.addBoost(new PassStore.Boost(System.currentTimeMillis() + duration.toMillis(), multiplier, uncapped, label));
        refreshEvents();
        Bukkit.getScheduler().runTask(plugin, () -> {
            bus.publish("events", new com.google.gson.JsonObject());
            announcer.broadcastEvent(duration, multiplier, uncapped);
        });
    }

    void stopEvents() {
        store.clearBoosts();
        refreshEvents();
        Bukkit.getScheduler().runTask(plugin, () -> bus.publish("events", new com.google.gson.JsonObject()));
    }

    /** Thread principal : relit le booster du joueur (eterreward.pass.boost.<pourcentage>, le plus grand). */
    void refreshBoost(Player player) {
        int best = 0;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String permission = info.getPermission();
            if (info.getValue() && permission.startsWith(BOOST_PERMISSION)) {
                try {
                    best = Math.max(best, Integer.parseInt(permission.substring(BOOST_PERMISSION.length())));
                } catch (NumberFormatException ignored) {
                    // eterreward.pass.boost.* ou autre : ignoré
                }
            }
        }
        if (best > 0) {
            boosts.put(player.getUniqueId(), Math.min(best, 500));
        } else {
            boosts.remove(player.getUniqueId());
        }
    }

    void forget(UUID player) {
        boosts.remove(player);
    }

    int boostOf(UUID player) {
        return boosts.getOrDefault(player, 0);
    }

    // ---------- Annonces de palier ----------

    /**
     * Un niveau vient peut-être d'être passé : une seule annonce par niveau (la base départage), en grand pour les
     * paliers clés, puis les objectifs de clan. Bloquant.
     */
    private void levelUp(UUID uuid, Season season, Progress before, int after, Source source) {
        int level = curve().level(after);
        int previousLevel = before.notified() != null ? before.notified() : curve().level(before.xp());
        if (level <= previousLevel || !store.markNotified(uuid, season.key(), level)) {
            return;
        }
        int from = previousLevel;
        Bukkit.getScheduler().runTask(plugin, () -> notifyLevel(uuid, from, level));
        if (source != Source.CLAN) {
            try {
                ClanGoals.check(this, uuid, season);
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().warning("Objectifs de clan du pass non vérifiés : " + e.getMessage());
            }
        }
    }

    /** Thread principal : l'annonce d'un niveau atteint, ici ou sur le serveur du joueur. */
    private void notifyLevel(UUID uuid, int from, int level) {
        int tiers = curve().tiers();
        boolean keyTier = false;
        for (int tier = from + 1; tier <= Math.min(level, tiers); tier++) {
            keyTier |= tier % KEY_TIER_EVERY == 0 || tier == tiers;
        }
        String key = level > tiers ? "pass.overflow-reached" : "pass.tier-reached";
        String shown = String.valueOf(level > tiers ? level - tiers : level);
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            bus.notify(uuid, key, true, "tier", shown);
            return;
        }
        messages.send(player, key, "tier", shown);
        Component button = messages.get(player, "pass.button")
                .clickEvent(ClickEvent.runCommand("/pass"))
                .hoverEvent(HoverEvent.showText(messages.get(player, "pass.button-hover")));
        player.sendMessage(messages.prefix().append(button));
        if (keyTier) {
            player.showTitle(Title.title(messages.get(player, "pass.key-tier.title", "tier", String.valueOf(Math.min(level, tiers))),
                    messages.get(player, "pass.key-tier.subtitle"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1f);
        } else {
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
        }
        publishTag(player);
    }

    // ---------- Sidebar ----------

    /** Ligne du pass dans la sidebar d'EterTab (<tag_pass>) : son palier. À l'arrivée et à chaque palier atteint. */
    public void publishTag(Player player) {
        if (!enabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            Season season = season();
            return new int[]{tier(store.xp(uuid, season.key())), curve().tiers()};
        }, tier -> {
            String format = messages.raw(player, "pass.sidebar");
            if (format != null) {
                EterLib.get().getPlayerTags().set(player, "pass", format.replace("<tier>", String.valueOf(tier[0]))
                        .replace("<max>", String.valueOf(tier[1])));
            }
        }, () -> { });
    }

    // ---------- Premium ----------

    /** Thread principal : les suffixes de eterreward.pass.premium.* que le joueur a (« * », « always », un mois, un pass). */
    Set<String> premiumPermissions(Player player) {
        Set<String> granted = new HashSet<>();
        if (player.hasPermission(PREMIUM_PERMISSION + "*")) {
            granted.add("*");
        }
        if (player.hasPermission(PREMIUM_ALWAYS)) {
            granted.add("always");
        }
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue() && info.getPermission().startsWith(PREMIUM_PERMISSION)) {
                granted.add(info.getPermission().substring(PREMIUM_PERMISSION.length()));
            }
        }
        return granted;
    }

    /** Premium pour cette saison : par permission (tous les mois, ce mois, ou l'ancienne permission par pass) ou en base. Bloquant. */
    boolean isPremium(UUID player, Set<String> permissions, Season season) {
        return permissions.contains("*") || permissions.contains("always") || permissions.contains(season.key())
                || permissions.contains(season.pass().id()) || store.hasPremium(player, season.key());
    }

    /**
     * Récompenses du pass en cours prêtes à réclamer (gratuites, et premium s'il l'a), pour un autre plugin (/menu).
     * Bloquant (base). Les permissions premium d'un joueur connecté sont lues ici : lecture seule, sûre avec LuckPerms.
     */
    public int claimableCount(UUID player) {
        Season season = enabled() ? season() : null;
        if (season == null) {
            return 0;
        }
        Player online = Bukkit.getPlayer(player);
        Set<String> permissions = online == null ? Set.of() : premiumPermissions(online);
        return claimableLines(season, store.xp(player, season.key()), store.claims(player, season.key()),
                isPremium(player, permissions, season)).size();
    }

    /** Colis en attente (récompenses mises de côté, /pass > boîte). Bloquant (base). */
    public int parcelCount(UUID player) {
        return store.parcelCount(player);
    }

    // ---------- Menu ----------

    public void open(Player player) {
        if (!enabled()) {
            messages.send(player, "pass.none");
            return;
        }
        open(player, Which.CURRENT, -1, false);
    }

    /** Le menu, relu en base. page -1 : la page du premier palier à réclamer (sinon du palier en cours). */
    void open(Player player, Which which, int page, boolean filter) {
        UUID uuid = player.getUniqueId();
        Set<String> permissions = premiumPermissions(player);
        refreshBoost(player);
        Tasks.async(plugin, player, () -> {
            Season season = switch (which) {
                case CURRENT -> season();
                case PREVIOUS -> previousSeason();
                case NEXT -> previewSeason();
            };
            if (season == null) {
                return null;
            }
            boolean next = which == Which.NEXT;
            int xp = next ? 0 : store.xp(uuid, season.key());
            Set<String> claimed = next ? Set.of() : store.claims(uuid, season.key());
            boolean isPremium = isPremium(uuid, permissions, season);
            int[] played = which == Which.CURRENT ? playedToday(uuid, season) : new int[]{0, 0};
            int previousClaimable = 0;
            if (which == Which.CURRENT) {
                Season before = previousSeason();
                if (before != null) {
                    previousClaimable = claimableLines(before, store.xp(uuid, before.key()), store.claims(uuid, before.key()),
                            isPremium(uuid, permissions, before)).size();
                }
            }
            boolean preview = which == Which.CURRENT && previewSeason() != null;
            return new View(which, season, xp, claimed, isPremium, played[0], played[1], store.parcelCount(uuid), previousClaimable,
                    preview, which == Which.CURRENT ? catchUp(tier(xp)) : 1, boostOf(uuid), eventMultiplier());
        }, view -> {
            if (view == null) {
                messages.send(player, which == Which.NEXT ? "pass.no-preview" : "pass.season-over");
                return;
            }
            player.openInventory(new PassMenu(this, player, view, page, filter).getInventory());
        }, () -> messages.send(player, "error.generic"));
    }

    /** Les lignes réclamables d'une saison pour ce joueur (paliers atteints, pas réclamés, premium si besoin, bonus). */
    List<Line> claimableLines(Season season, int xp, Set<String> claimed, boolean isPremium) {
        List<Line> lines = new ArrayList<>();
        int reached = tier(xp);
        for (int number = 1; number <= reached; number++) {
            Tier tier = season.pass().tiers().get(number - 1);
            if (tier.free() != null && !claimed.contains(String.valueOf(number))) {
                lines.add(new Line(number, false));
            }
            if (tier.premium() != null && isPremium && !claimed.contains(number + "p")) {
                lines.add(new Line(number, true));
            }
        }
        int tiers = curve().tiers();
        for (int bonus = 1; bonus <= curve().overflow(xp); bonus++) {
            if (!claimed.contains(String.valueOf(tiers + bonus))) {
                lines.add(new Line(tiers + bonus, false));
            }
        }
        return lines;
    }

    /** La récompense d'une ligne (palier bonus : celle de pass.overflow) ; null s'il n'y en a pas. */
    Reward rewardOf(Season season, Line line) {
        if (line.tier() > curve().tiers()) {
            return line.premium() ? null : overflowReward();
        }
        if (line.tier() < 1) {
            return null;
        }
        Tier tier = season.pass().tiers().get(line.tier() - 1);
        return line.premium() ? tier.premium() : tier.free();
    }

    Reward overflowReward() {
        PassSettings s = settings;
        return new Reward(s.overflowMoney(), s.overflowKeys(), 0, List.of(), List.of(), List.of(), null);
    }

    // ---------- Réclamations ----------

    /** Ce qu'une réclamation a donné : chaque ligne et sa partie sur place ; failed : lignes annulées (don impossible). */
    private record Claimed(List<Line> lines, List<Reward> rewards, List<Parcel> local, int failed, boolean boxed, String error) {
    }

    /**
     * Réclame des lignes (thread principal) ; lines vide : tout ce qui est réclamable. Les vérifications sont refaites en
     * base : palier atteint, premium, saison encore réclamable.
     */
    void claim(Player player, Which which, Season shown, List<Line> lines, int page, boolean filter) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Set<String> permissions = premiumPermissions(player);
        String server = EterLib.get().getServerName();
        Tasks.async(plugin, player, () -> {
            Season season = claimable(shown.key());
            if (season == null) {
                return new Claimed(List.of(), List.of(), List.of(), 0, false, "pass.season-over");
            }
            int xp = store.xp(uuid, season.key());
            Set<String> claimed = store.claims(uuid, season.key());
            boolean isPremium = isPremium(uuid, permissions, season);
            List<Line> wanted = lines.isEmpty() ? claimableLines(season, xp, claimed, isPremium) : lines;
            if (wanted.isEmpty()) {
                return new Claimed(List.of(), List.of(), List.of(), 0, false, lines.isEmpty() ? "pass.nothing" : "pass.already");
            }
            if (lines.size() == 1) {
                Line line = lines.getFirst();
                if (curve().level(xp) < line.tier() || line.tier() > curve().tiers() && line.premium()) {
                    return new Claimed(List.of(), List.of(), List.of(), 0, false, "pass.locked");
                }
                if (line.premium() && !isPremium) {
                    return new Claimed(List.of(), List.of(), List.of(), 0, false, "pass.premium-required");
                }
            }
            List<Line> done = new ArrayList<>();
            List<Reward> given = new ArrayList<>();
            List<Parcel> local = new ArrayList<>();
            int failed = 0;
            boolean boxed = false;
            for (Line line : wanted) {
                Reward reward = rewardOf(season, line);
                if (reward == null || curve().level(xp) < line.tier() || line.premium() && !isPremium
                        || !store.claim(uuid, season.key(), line.tier(), line.premium(), server)) {
                    continue;
                }
                Parcel parcel = Parcel.of(season.key(), label(line.tier(), line.premium()), reward);
                try {
                    Parcel left = rewards.giveRemote(uuid, name, parcel.remotePart());
                    if (!left.isEmpty()) {
                        store.addParcel(uuid, left);
                        boxed = true;
                    }
                } catch (PassRewards.GiveException e) {
                    store.unclaim(uuid, season.key(), line.tier(), line.premium());
                    plugin.getLogger().severe("Pass : " + e.getMessage() + " (réclamation annulée) : " + e.getCause());
                    failed++;
                    continue;
                }
                done.add(line);
                given.add(reward);
                local.add(parcel.localPart());
            }
            if (done.isEmpty() && failed == 0) {
                return new Claimed(List.of(), List.of(), List.of(), 0, false, "pass.already");
            }
            return new Claimed(done, given, local, failed, boxed, null);
        }, result -> {
            if (result.error() != null) {
                messages.send(player, result.error());
                Sounds.refuse(player);
            } else {
                deliverClaimed(player, result);
            }
            open(player, which, page, filter);
        }, () -> messages.send(player, "error.generic"), result -> result.local().forEach(parcel -> {
            // Réclamée, mais parti avant de recevoir les objets : ils l'attendent dans la boîte
            if (!parcel.isEmpty()) {
                Tasks.async(plugin, () -> store.addParcel(uuid, parcel), "Récompense du pass non mise en colis pour " + name);
            }
        }));
    }

    /** Thread principal : les objets et commandes de ce qui vient d'être réclamé, puis le message. */
    private void deliverClaimed(Player player, Claimed result) {
        boolean boxed = result.boxed();
        for (Parcel parcel : result.local()) {
            Parcel left = rewards.giveLocal(player, parcel, this);
            if (!left.isEmpty()) {
                boxed = true;
                UUID uuid = player.getUniqueId();
                Tasks.async(plugin, () -> store.addParcel(uuid, left), "Reste d'une récompense du pass non mis en colis");
            }
        }
        if (result.lines().size() == 1) {
            Line line = result.lines().getFirst();
            player.sendMessage(messages.prefix().append(messages.render(raw(player, "pass.claimed"),
                    Placeholder.component("content", rewards.describe(player, result.rewards().getFirst())),
                    "tier", tierLabel(player, line.tier()))));
        } else if (!result.lines().isEmpty()) {
            messages.send(player, "pass.claimed-all", "count", String.valueOf(result.lines().size()));
            for (int i = 0; i < result.lines().size(); i++) {
                Line line = result.lines().get(i);
                player.sendMessage(messages.render(raw(player, line.premium() ? "pass.claimed-line-premium" : "pass.claimed-line"),
                        Placeholder.component("content", rewards.describe(player, result.rewards().get(i))),
                        "tier", tierLabel(player, line.tier())));
            }
        }
        if (result.failed() > 0) {
            messages.send(player, "pass.give-failed", "count", String.valueOf(result.failed()));
        }
        if (boxed) {
            boxButton(player, "pass.boxed");
        }
        if (!result.lines().isEmpty()) {
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
    }

    /** « 12 » ou « bonus 3 ». */
    String tierLabel(Player viewer, int tier) {
        int tiers = curve().tiers();
        return tier > tiers ? messages.plain(viewer, "pass.bonus-tier", "tier", String.valueOf(tier - tiers)) : String.valueOf(tier);
    }

    /** Étiquette d'un colis de palier, rendue dans la langue du joueur à l'affichage. */
    static String label(int tier, boolean premium) {
        return "tier:" + tier + ":" + (premium ? "premium" : "free");
    }

    // ---------- Boîte de colis ----------

    void openBox(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> store.parcels(uuid),
                parcels -> player.openInventory(new ParcelMenu(this, player, parcels).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    /** Prend des colis (tous si parcels en contient plusieurs), thread principal. */
    void takeParcels(Player player, List<Parcel> parcels) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Tasks.async(plugin, player, () -> {
            List<Parcel> local = new ArrayList<>();
            boolean failed = false;
            for (Parcel parcel : parcels) {
                if (!store.takeParcel(uuid, parcel.id())) {
                    continue;
                }
                try {
                    Parcel left = rewards.giveRemote(uuid, name, parcel.remotePart());
                    if (!left.isEmpty()) {
                        store.addParcel(uuid, left);
                    }
                    local.add(parcel.localPart());
                } catch (PassRewards.GiveException e) {
                    store.addParcel(uuid, parcel);
                    plugin.getLogger().severe("Pass : colis non donné à " + name + " : " + e.getMessage());
                    failed = true;
                }
            }
            return new Object[]{local, failed};
        }, result -> {
            @SuppressWarnings("unchecked") List<Parcel> local = (List<Parcel>) result[0];
            boolean full = false;
            for (Parcel parcel : local) {
                Parcel left = rewards.giveLocal(player, parcel, this);
                if (!left.isEmpty()) {
                    full = true;
                    Tasks.async(plugin, () -> store.addParcel(uuid, left), "Reste d'un colis non remis dans la boîte");
                }
            }
            if (!local.isEmpty()) {
                messages.send(player, "pass.box.taken", "count", String.valueOf(local.size()));
                player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1f);
            }
            if (full) {
                messages.send(player, "pass.box.full");
            }
            if ((boolean) result[1]) {
                messages.send(player, "pass.give-failed", "count", "1");
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> openBox(player), 5);
        }, () -> messages.send(player, "error.generic"), result -> {
            @SuppressWarnings("unchecked") List<Parcel> local = (List<Parcel>) result[0];
            local.stream().filter(parcel -> !parcel.isEmpty()).forEach(parcel ->
                    Tasks.async(plugin, () -> store.addParcel(uuid, parcel), "Colis non remis dans la boîte"));
        });
    }

    /** Message avec un bouton qui ouvre la boîte. */
    void boxButton(Player player, String key, String... placeholders) {
        Component button = messages.get(player, "pass.box.button")
                .clickEvent(ClickEvent.runCommand("/pass box"))
                .hoverEvent(HoverEvent.showText(messages.get(player, "pass.box.button-hover")));
        player.sendMessage(messages.prefix().append(messages.get(player, key, placeholders)).append(Component.space()).append(button));
    }

    // ---------- Arrivée ----------

    /** Arrivée (thread principal) : booster, sidebar, puis rappels (colis, paliers à réclamer, mois d'avant). */
    void joined(Player player) {
        refreshBoost(player);
        if (!enabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Set<String> permissions = premiumPermissions(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            publishTag(player);
            Tasks.async(plugin, player, () -> {
                Season season = season();
                int ready = claimableLines(season, store.xp(uuid, season.key()), store.claims(uuid, season.key()),
                        isPremium(uuid, permissions, season)).size();
                Season before = previousSeason();
                int old = before == null ? 0 : claimableLines(before, store.xp(uuid, before.key()), store.claims(uuid, before.key()),
                        isPremium(uuid, permissions, before)).size();
                return new int[]{ready, old, store.parcelCount(uuid)};
            }, counts -> {
                if (counts[0] > 0) {
                    passButton(player, "pass.reminder", "count", String.valueOf(counts[0]));
                }
                if (counts[1] > 0) {
                    passButton(player, "pass.reminder-previous", "count", String.valueOf(counts[1]),
                            "days", String.valueOf(settings.graceDays()));
                }
                if (counts[2] > 0) {
                    boxButton(player, "pass.box.reminder", "count", String.valueOf(counts[2]));
                }
            }, () -> { });
        }, 60);
    }

    /** Message avec un bouton qui ouvre /pass. */
    void passButton(Player player, String key, String... placeholders) {
        Component button = messages.get(player, "pass.button")
                .clickEvent(ClickEvent.runCommand("/pass"))
                .hoverEvent(HoverEvent.showText(messages.get(player, "pass.button-hover")));
        player.sendMessage(messages.prefix().append(messages.get(player, key, placeholders)).append(Component.space()).append(button));
    }

    // ---------- Historique ----------

    /** /pass history : l'XP des dernières 24 h, par source. */
    void history(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> store.history(uuid, 24), sums -> {
            if (sums.isEmpty()) {
                messages.send(player, "pass.history.empty");
                return;
            }
            messages.send(player, "pass.history.title", "total", String.valueOf(sums.values().stream().mapToInt(Integer::intValue).sum()));
            sums.forEach((source, amount) -> player.sendMessage(messages.get(player, "pass.history.line",
                    "source", messages.plain(player, "pass.source." + source), "xp", String.valueOf(amount))));
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Accès ----------

    /** Temps avant la fin de la saison, mis en forme. */
    String timeLeft(Player viewer, Season season) {
        return EterLib.get().formatDuration(viewer, Math.max(0, secondsLeft(season)));
    }

    long secondsLeft(Season season) {
        return Duration.between(ZonedDateTime.now(zone), season.end()).toSeconds();
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(zone);
    }

    PassRewards rewards() {
        return rewards;
    }

    PassStore store() {
        return store;
    }

    PassSettings settings() {
        return settings;
    }

    PassPremium premium() {
        return premium;
    }

    NetworkBus bus() {
        return bus;
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

    String raw(Player player, String key) {
        String raw = messages.raw(player, key);
        return raw == null ? key : raw;
    }
}
