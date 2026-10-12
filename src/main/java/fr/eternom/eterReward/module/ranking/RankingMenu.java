package fr.eternom.eterReward.module.ranking;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterReward.module.ranking.RankingService.Line;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Menu /classement : un bouton par classement, avec son top 3 ; le temps restant avant la fin du mois en haut. */
class RankingMenu implements Menu {

    private static final int INFO = 4;
    private static final int[] SLOTS = {20, 21, 22, 23, 24, 31};
    private static final int BACK = 49;

    private final RankingService service;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Boards> shown = new HashMap<>();

    RankingMenu(RankingService service, Player viewer) {
        this.service = service;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, text("ranking.menu.title"));
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(INFO, Items.item(Material.CLOCK, text("ranking.menu.info"),
                List.of(text("ranking.menu.ends", "time", service.timeLeft(viewer)), text("ranking.menu.rule"))));
        int i = 0;
        for (Boards board : Boards.values()) {
            if (!service.available(board) || i >= SLOTS.length) {
                continue;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(text(board.monthly() ? "ranking.menu.monthly" : "ranking.menu.forever"));
            lore.add(Component.empty());
            List<Line> top = service.top(board);
            for (int place = 0; place < 3; place++) {
                lore.add(place < top.size()
                        ? text("ranking.menu.line", "place", String.valueOf(place + 1), "player", top.get(place).name(),
                        "value", BoardMenu.format(top.get(place).value()))
                        : text("ranking.menu.empty-line", "place", String.valueOf(place + 1)));
            }
            lore.add(Component.empty());
            lore.add(text("ranking.menu.open"));
            inventory.setItem(SLOTS[i], Items.item(board.icon(), text("ranking.board." + board.id()), lore));
            shown.put(SLOTS[i], board);
            i++;
        }
        inventory.setItem(BACK, service.backButton().item(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            service.backButton().click(player);
            return;
        }
        Boards board = shown.get(slot);
        if (board != null) {
            Sounds.click(player);
            service.openBoard(player, board);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private Component text(String key, String... placeholders) {
        return service.messages().get(viewer, key, placeholders);
    }
}
