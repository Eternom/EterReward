package fr.eternom.eterReward.module.ranking;

import com.google.gson.JsonObject;
import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.module.pass.PassService;
import fr.eternom.eterReward.module.ranking.RankingStore.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Les classements (/classement). Les tops sont recalculés toutes les 5 minutes en tâche de fond et gardés en mémoire :
 * ouvrir le menu ne charge pas la base. La place du joueur est lue à l'ouverture.
 *
 * Fin du mois : le premier serveur qui voit le nouveau mois (et qui a EterResource, pour les clés) réserve les
 * récompenses de chaque classement mensuel (INSERT IGNORE : un seul serveur les donne), donne au top 3 du mois fini de
 * l'argent, des clés de ressource et de l'XP du pass du nouveau mois, et l'annonce sur tout le réseau.
 */
public class RankingService {

    public static final int SIZE = 10;
    private static final DateTimeFormatter SEASON = DateTimeFormatter.ofPattern("yyyy-MM");

    /** Récompense d'une place du top 3 (config.yml > ranking.rewards.<place>). */
    public record Reward(double money, int keys, int passXp) {
    }

    /**
     * Réglages (config.yml > ranking) : les récompenses du top 3, et les classements du mois récompensés (rewarded,
     * le général si absent : une ancienne config ne doit pas couper les récompenses).
     */
    public record Settings(List<Reward> rewards, Set<Boards> rewarded) {

        public static Settings load(ConfigurationSection config, Logger logger) {
            List<Reward> rewards = new ArrayList<>();
            for (int place = 1; place <= 3; place++) {
                String path = "ranking.rewards." + place + ".";
                rewards.add(new Reward(Math.max(0, config.getDouble(path + "money")), Math.max(0, config.getInt(path + "keys")),
                        Math.max(0, config.getInt(path + "pass-xp"))));
            }
            Set<Boards> rewarded = EnumSet.noneOf(Boards.class);
            List<String> ids = config.isSet("ranking.rewarded") ? config.getStringList("ranking.rewarded") : List.of(Boards.GENERAL.id());
            for (String id : ids) {
                Arrays.stream(Boards.values()).filter(board -> board.monthly() && board.id().equalsIgnoreCase(id)).findFirst()
                        .ifPresentOrElse(rewarded::add, () -> logger.warning("ranking.rewarded : classement du mois inconnu " + id));
            }
            return new Settings(List.copyOf(rewards), rewarded);
        }
    }

    /** Une ligne d'un classement : joueur, pseudo, valeur. */
    public record Line(UUID player, String name, long value, Material icon) {
    }

    private final JavaPlugin plugin;
    private final RankingStore store;
    private final PassService pass;
    private final Messages messages;
    private final ZoneId zone;
    private final BackButton backButton;
    private final List<Reward> rewards;
    private final NetworkBus bus;
    private final Set<Boards> rewarded;
    private volatile Map<Boards, List<Line>> cache = new EnumMap<>(Boards.class);
    /** Classement général du mois : joueur -> {place, valeur en dixièmes}. */
    private volatile Map<UUID, long[]> standings = new HashMap<>();
    /** Le top du général à chaque calcul (podium d'EterNpc), thread principal. */
    private Consumer<List<Line>> onGeneral = top -> { };

    public RankingService(JavaPlugin plugin, RankingStore store, PassService pass, Messages messages, ZoneId zone,
                          BackButton backButton, Settings settings) {
        this.plugin = plugin;
        this.store = store;
        this.pass = pass;
        this.messages = messages;
        this.zone = zone;
        this.backButton = backButton;
        this.rewards = settings.rewards();
        this.rewarded = settings.rewarded();
        this.bus = EterLib.get().network(plugin, "eterreward", messages);
        // Annonce des gagnants du mois, envoyée par le serveur qui donne les récompenses
        bus.on("ranking-winners", data -> announce(data.get("key").getAsString(), data.get("first").getAsString(),
                data.get("second").getAsString(), data.get("third").getAsString()));
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 20 * 10, 20 * 60 * 5);
    }

    String season() {
        return YearMonth.now(zone).format(SEASON);
    }

    /** Toutes les 5 minutes (tâche de fond) : les tops, les places du général, puis les récompenses du mois fini. */
    private void refresh() {
        Boards.Context context = context(season());
        Map<Boards, List<Line>> fresh = new EnumMap<>(Boards.class);
        for (Boards board : Boards.values()) {
            try {
                if (board.available()) {
                    fresh.put(board, lines(board, board.top(context, SIZE)));
                }
            } catch (RuntimeException | LinkageError e) {
                // LinkageError : le plugin qui possède les données est trop ancien (son API n'a pas encore le classement)
                plugin.getLogger().warning("Classement " + board.id() + " illisible : " + e);
            }
        }
        cache = fresh;
        refreshGeneral(context, fresh.getOrDefault(Boards.GENERAL, List.of()));
        try {
            giveMonthlyRewards();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Récompenses des classements non données : " + e.getMessage());
        }
    }

    /**
     * Le général a aussi besoin de TOUTES les places (placeholder « ta place », pour n'importe quel joueur) : gardées en
     * mémoire, et le top donné au podium.
     */
    private void refreshGeneral(Boards.Context context, List<Line> top) {
        try {
            standings = GeneralRanking.standings(GeneralRanking.compute(context));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Places du classement général illisibles : " + e);
        }
        Bukkit.getScheduler().runTask(plugin, () -> onGeneral.accept(top));
    }

    private Boards.Context context(String season) {
        return new Boards.Context(store, season, pass::tierFromXp);
    }

    private List<Line> lines(Boards board, List<Entry> entries) {
        return entries.stream().map(entry -> {
            String label = board.label(entry);
            return new Line(entry.player(), label != null ? label : name(entry.player()), entry.value(), board.lineIcon(entry));
        }).toList();
    }

    private static String name(UUID uuid) {
        return EterLib.get().getPlayers().get(uuid).map(found -> found.name())
                .orElseGet(() -> {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                    return offline.getName() == null ? "?" : offline.getName();
                });
    }

    /** Une seule fois par mois et par classement, sur un serveur qui peut donner des clés de ressource. */
    private void giveMonthlyRewards() {
        if (!Bukkit.getPluginManager().isPluginEnabled("EterResource")) {
            return;
        }
        Boards.Context previous = context(YearMonth.now(zone).minusMonths(1).format(SEASON));
        for (Boards board : Boards.values()) {
            if (!rewarded.contains(board) || !store.claimRewards(previous.season(), board.id())) {
                continue;
            }
            List<Entry> top = board.top(previous, rewards.size());
            String[] names = {"-", "-", "-"};
            for (int place = 0; place < top.size() && place < rewards.size(); place++) {
                UUID winner = top.get(place).player();
                Reward reward = rewards.get(place);
                if (reward.money() > 0) {
                    EconomyApi.get().ifPresent(economy -> economy.deposit(winner, reward.money(), "EterReward · classement"));
                }
                if (reward.keys() > 0) {
                    ResourceKeys.give(winner, reward.keys());
                }
                if (reward.passXp() > 0) {
                    pass.addXp(winner, reward.passXp());
                }
                names[Math.min(place, 2)] = name(winner);
                bus.notify(winner, "ranking.winner." + board.id(), true, "place", String.valueOf(place + 1),
                        "money", Money.format(reward.money()), "keys", String.valueOf(reward.keys()));
            }
            if (!top.isEmpty()) {
                String key = "ranking.announce." + board.id();
                JsonObject data = new JsonObject();
                data.addProperty("key", key);
                data.addProperty("first", names[0]);
                data.addProperty("second", names[1]);
                data.addProperty("third", names[2]);
                bus.publish("ranking-winners", data);
                announce(key, names[0], names[1], names[2]); // le bus ne renvoie pas au serveur qui publie
            }
        }
    }

    private void announce(String key, String first, String second, String third) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(player ->
                messages.send(player, key, "first", first, "second", second, "third", third)));
    }

    // ---------- Menu ----------

    /** Le top en mémoire d'un classement (vide tant qu'il n'a pas été calculé). */
    List<Line> top(Boards board) {
        return cache.getOrDefault(board, List.of());
    }

    boolean available(Boards board) {
        return cache.containsKey(board);
    }

    public void open(Player player) {
        player.openInventory(new RankingMenu(this, player).getInventory());
    }

    void openBoard(Player player, Boards board) {
        UUID uuid = player.getUniqueId();
        Boards.Context context = context(season());
        Tasks.async(plugin, player, () -> {
                    try {
                        return board.of(context, uuid);
                    } catch (LinkageError tooOld) {
                        return new long[]{0, 0}; // plugin des données trop ancien : pas de place
                    }
                },
                mine -> player.openInventory(new BoardMenu(this, player, board, mine).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    public String timeLeft(CommandSender viewer) {
        ZonedDateTime end = YearMonth.now(zone).plusMonths(1).atDay(1).atStartOfDay(zone);
        return EterLib.get().formatDuration(viewer, Math.max(0, Duration.between(ZonedDateTime.now(zone), end).toSeconds()));
    }

    List<Reward> rewards() {
        return rewards;
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

    /** Une quête de métier réussie (tâche de fond) : comptée pour le classement du mois. */
    public void questCompleted(UUID player) {
        store.addMonthly(player, season(), Boards.QUESTS.id());
    }

    /** Récompensé en fin de mois (config.yml > ranking.rewarded). */
    boolean rewarded(Boards board) {
        return rewarded.contains(board);
    }

    /** {place (0 si absent), valeur en dixièmes} du joueur dans le classement général du mois. */
    public long[] standing(UUID player) {
        return standings.getOrDefault(player, new long[]{0, 0});
    }

    /** Nombre de joueurs classés au général ce mois-ci. */
    public int generalPlayers() {
        return standings.size();
    }

    /** Le top 10 du classement général du mois (en mémoire). */
    public List<Line> generalTop() {
        return top(Boards.GENERAL);
    }

    /** Appelé à chaque calcul du général avec son top, sur le thread principal (podium). */
    public void onGeneral(Consumer<List<Line>> listener) {
        this.onGeneral = listener;
    }

    /** Un défi réussi (tâche de fond de ChallengeService) : compté pour le classement du mois. */
    public void challengeCompleted(UUID player) {
        store.addChallenge(player, season());
    }
}
