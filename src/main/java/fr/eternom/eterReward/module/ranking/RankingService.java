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
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** Une ligne d'un classement : joueur, pseudo, valeur. */
    record Line(UUID player, String name, long value) {
    }

    private final JavaPlugin plugin;
    private final RankingStore store;
    private final PassService pass;
    private final Messages messages;
    private final ZoneId zone;
    private final BackButton backButton;
    private final List<Reward> rewards;
    private final NetworkBus bus;
    private volatile Map<Boards, List<Line>> cache = new EnumMap<>(Boards.class);

    public RankingService(JavaPlugin plugin, RankingStore store, PassService pass, Messages messages, ZoneId zone,
                          BackButton backButton, List<Reward> rewards) {
        this.plugin = plugin;
        this.store = store;
        this.pass = pass;
        this.messages = messages;
        this.zone = zone;
        this.backButton = backButton;
        this.rewards = rewards;
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

    /** Toutes les 5 minutes (tâche de fond) : les tops, puis les récompenses du mois fini si besoin. */
    private void refresh() {
        String season = season();
        Map<Boards, List<Line>> fresh = new EnumMap<>(Boards.class);
        for (Boards board : Boards.values()) {
            try {
                if (board.available()) {
                    fresh.put(board, lines(board.top(store, season, SIZE)));
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Classement " + board.id() + " illisible : " + e.getMessage());
            }
        }
        cache = fresh;
        try {
            giveMonthlyRewards();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Récompenses des classements non données : " + e.getMessage());
        }
    }

    private List<Line> lines(List<Entry> entries) {
        return entries.stream().map(entry -> new Line(entry.player(), name(entry.player()), entry.value())).toList();
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
        String previous = YearMonth.now(zone).minusMonths(1).format(SEASON);
        for (Boards board : Boards.values()) {
            if (!board.monthly() || !store.claimRewards(previous, board.id())) {
                continue;
            }
            List<Entry> top = board.top(store, previous, rewards.size());
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
        String season = season();
        Tasks.async(plugin, player, () -> board.of(store, season, uuid),
                mine -> player.openInventory(new BoardMenu(this, player, board, mine).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    String timeLeft(Player viewer) {
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

    /** Un défi réussi (tâche de fond de ChallengeService) : compté pour le classement du mois. */
    public void challengeCompleted(UUID player) {
        store.addChallenge(player, season());
    }
}
