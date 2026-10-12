package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterReward.module.ranking.RankingService.Line;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Placeholders du classement général (PlaceholderAPI), pour un hologramme ou un scoreboard. Lus en mémoire, jamais en
 * base. Classe chargée seulement si PlaceholderAPI tourne.
 * - %eterreward_general_rank% / %eterreward_general_score% : la place et le score de celui qui regarde ("-" s'il
 *   n'est pas classé) ;
 * - %eterreward_general_top_<1-10>_name% / _score% ; %eterreward_general_players% : nombre de joueurs classés ;
 * - %eterreward_general_timeleft% : temps avant la fin du mois.
 */
public final class RankingPlaceholders extends PlaceholderExpansion {

    private static final String NONE = "-";

    private final JavaPlugin plugin;
    private final RankingService ranking;

    public RankingPlaceholders(JavaPlugin plugin, RankingService ranking) {
        this.plugin = plugin;
        this.ranking = ranking;
    }

    @Override
    public String getIdentifier() {
        return "eterreward";
    }

    @Override
    public String getAuthor() {
        return "NadTum";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Reste enregistré après un /papi reload. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (!params.startsWith("general_")) {
            return null;
        }
        String key = params.substring("general_".length());
        switch (key) {
            case "rank", "score" -> {
                long[] mine = player == null ? new long[]{0, 0} : ranking.standing(player.getUniqueId());
                if (mine[0] == 0) {
                    return NONE;
                }
                return key.equals("rank") ? String.valueOf(mine[0]) : Boards.GENERAL.display(mine[1]);
            }
            case "players" -> {
                return String.valueOf(ranking.generalPlayers());
            }
            case "timeleft" -> {
                CommandSender viewer = player != null && player.getPlayer() != null ? player.getPlayer() : Bukkit.getConsoleSender();
                return ranking.timeLeft(viewer);
            }
            default -> {
                return top(key);
            }
        }
    }

    /** top_<n>_name, top_<n>_score. */
    private String top(String key) {
        String[] parts = key.split("_");
        if (parts.length != 3 || !parts[0].equals("top")) {
            return null;
        }
        int place;
        try {
            place = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        List<Line> top = ranking.generalTop();
        if (place < 1 || place > top.size()) {
            return NONE;
        }
        Line line = top.get(place - 1);
        return switch (parts[2]) {
            case "name" -> line.name();
            case "score" -> Boards.GENERAL.display(line.value());
            default -> null;
        };
    }
}
