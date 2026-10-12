package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterReward.module.ranking.RankingService.Line;
import fr.eternom.eterReward.module.ranking.RankingService.Reward;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Un classement : le top 10 (têtes des joueurs, la pile = la place), ta place et ta valeur, et pour un classement du
 * mois les récompenses du top 3.
 */
class BoardMenu implements Menu {

    private static final int[] SLOTS = {13, 21, 23, 29, 30, 31, 32, 33, 39, 41};
    private static final int MINE = 4;
    private static final int REWARDS = 45;
    private static final int BACK = 49;

    private final RankingService service;
    private final Player viewer;
    private final Inventory inventory;

    BoardMenu(RankingService service, Player viewer, Boards board, long[] mine) {
        this.service = service;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, text("ranking.board-title", "board",
                service.messages().plain(viewer, "ranking.board." + board.id())));
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);

        List<Line> top = service.top(board);
        for (int place = 0; place < top.size() && place < SLOTS.length; place++) {
            Line line = top.get(place);
            Component title = text("ranking.entry.name", "place", String.valueOf(place + 1), "player", line.name());
            List<Component> lore = List.of(text("ranking.unit." + board.id(), "value", format(line.value())));
            // Une ligne qui n'est pas un joueur (un clan) a son icône à la place d'une tête
            ItemStack head = line.icon() != null ? Items.item(line.icon(), title, lore)
                    : Items.head(Bukkit.createProfile(line.player(), line.name()), title, lore);
            head.setAmount(place + 1);
            inventory.setItem(SLOTS[place], head);
        }
        if (top.isEmpty()) {
            inventory.setItem(22, Items.item(Material.BARRIER, text("ranking.entry.none"), List.of()));
        }

        List<Component> lore = new ArrayList<>();
        lore.add(mine[0] > 0 ? text("ranking.mine.place", "place", String.valueOf(mine[0])) : text("ranking.mine.none"));
        lore.add(text("ranking.unit." + board.id(), "value", format(mine[1])));
        inventory.setItem(MINE, Items.head(viewer.getPlayerProfile(), text("ranking.mine.name", "player", viewer.getName()), lore));

        if (board.monthly()) {
            List<Component> rewards = new ArrayList<>();
            rewards.add(text("ranking.rewards.ends", "time", service.timeLeft(viewer)));
            for (int place = 0; place < service.rewards().size(); place++) {
                Reward reward = service.rewards().get(place);
                rewards.add(text("ranking.rewards.line", "place", String.valueOf(place + 1), "money", Money.format(reward.money()),
                        "keys", String.valueOf(reward.keys()), "xp", String.valueOf(reward.passXp())));
            }
            inventory.setItem(REWARDS, Items.item(Material.CHEST, text("ranking.rewards.title"), rewards));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, text("ranking.back"), List.of()));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Sounds.click(player);
            service.open(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** 1 234 567 */
    static String format(long value) {
        return String.format(Locale.FRANCE, "%,d", value).replace(' ', ' ').replace(' ', ' ');
    }

    private Component text(String key, String... placeholders) {
        return service.messages().get(viewer, key, placeholders);
    }
}
