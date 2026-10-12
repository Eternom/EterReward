package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterNpc.api.NpcApi;
import fr.eternom.eterNpc.api.NpcLook;
import fr.eternom.eterReward.module.ranking.RankingService.Line;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Le podium du classement général : les 5 premiers du mois en PNJ (EterNpc), avec leur skin, leur nom et leur score,
 * mis à jour à chaque calcul. Une place vide garde un PNJ « ??? ». Les places se posent avec /eterreward podium <1-5>
 * (eterreward:podium_<place>) et se retirent avec /eternpc remove. Classe chargée seulement si EterNpc tourne.
 */
public final class Podium {

    public static final int PLACES = 5;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final NpcApi npcs;

    public Podium(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.npcs = NpcApi.get().orElseThrow();
        update(List.of());
    }

    /** Thread principal. */
    public void update(List<Line> top) {
        CommandSender console = Bukkit.getConsoleSender();
        for (int place = 1; place <= PLACES; place++) {
            Line line = place <= top.size() ? top.get(place - 1) : null;
            String number = String.valueOf(place);
            npcs.define(plugin, new NpcLook(id(place),
                    line == null ? messages.get(console, "ranking.podium.empty", "place", number)
                            : messages.get(console, "ranking.podium.name", "place", number, "player", line.name()),
                    line == null ? null : messages.get(console, "ranking.podium.score", "value", Boards.GENERAL.display(line.value())),
                    line == null ? null : line.name(), null));
        }
    }

    /** /eterreward podium <place> : la place se pose là où est le staff, dans sa direction. */
    public void place(Player admin, int place) {
        npcs.place(id(place), admin.getLocation(), () -> messages.send(admin, "ranking.podium.placed", "place", String.valueOf(place)));
    }

    private static String id(int place) {
        return "eterreward:podium_" + place;
    }
}
