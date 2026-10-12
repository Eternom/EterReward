package fr.eternom.eterReward.module.challenge;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterReward.module.challenge.ChallengeService.Active;
import fr.eternom.eterReward.module.challenge.ChallengeService.View;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu /defis, 5 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ☀ ▢ ▢ ▣ ▣     ☀ = défis du jour (temps restant)
 *  ▣ · · d d d · · ▣     d = défis du jour
 *  ▢ ▢ ▢ ▢ ☾ ▢ ▢ ▢ ▢     ☾ = défis de la semaine (temps restant)
 *  ▣ · · w w w · · ▣     w = défis de la semaine
 *  ▣ ▣ ▢ ▢ « ▢ ▢ ▣ ▣
 * </pre>
 */
class ChallengeMenu implements Menu {

    private static final int DAILY_HEADER = 4;
    private static final int DAILY_ROW = 12;
    private static final int WEEKLY_HEADER = 22;
    private static final int WEEKLY_ROW = 30;
    private static final int BACK = 40;
    private static final int BAR = 10;

    private final ChallengeService service;
    private final Player viewer;
    private final Inventory inventory;

    ChallengeMenu(ChallengeService service, Player viewer, View view) {
        this.service = service;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 45, text("challenges.menu.title"));
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(DAILY_HEADER, header(view.daily(), Material.SUNFLOWER, "challenges.menu.daily"));
        inventory.setItem(WEEKLY_HEADER, header(view.weekly(), Material.CLOCK, "challenges.menu.weekly"));
        fill(view, view.daily(), DAILY_ROW);
        fill(view, view.weekly(), WEEKLY_ROW);
        inventory.setItem(BACK, service.backButton().item(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            service.backButton().click(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private ItemStack header(Active active, Material icon, String key) {
        return Items.item(icon, text(key), List.of(text("challenges.menu.ends", "time", service.timeLeft(viewer, active))));
    }

    private void fill(View view, Active active, int firstSlot) {
        List<ChallengeDefinition> challenges = active.challenges();
        if (challenges.isEmpty()) {
            inventory.setItem(firstSlot + 1, Items.item(Material.BARRIER, text("challenges.menu.none"), List.of()));
            return;
        }
        for (int i = 0; i < challenges.size() && i < 5; i++) {
            ChallengeDefinition challenge = challenges.get(i);
            int[] progress = view.progress().getOrDefault(active.key() + "|" + challenge.id(), new int[]{0, 0});
            int done = Math.min(progress[0], challenge.target());
            boolean completed = progress[1] == 1;
            List<Component> lore = new ArrayList<>();
            lore.add(text("challenges.menu.progress", "bar", bar(done, challenge.target()),
                    "done", String.valueOf(done), "target", String.valueOf(challenge.target())));
            lore.add(text("challenges.menu.reward", "xp", String.valueOf(challenge.xp()), "money", Money.format(challenge.money())));
            lore.add(Component.empty());
            lore.add(text(completed ? "challenges.menu.completed" : done >= challenge.target()
                    ? "challenges.menu.validating" : "challenges.menu.ongoing"));
            ItemStack item = Items.item(completed ? Material.LIME_DYE : challenge.icon(),
                    text("challenges.list." + challenge.id(), "target", String.valueOf(challenge.target())), lore, completed);
            inventory.setItem(firstSlot + i, item);
        }
    }

    /** Barre de 10 cases : ■■■■□□□□□□ */
    private static String bar(int done, int target) {
        int filled = (int) Math.floor(BAR * (double) done / target);
        return "■".repeat(filled) + "□".repeat(BAR - filled);
    }

    private Component text(String key, String... placeholders) {
        return service.messages().get(viewer, key, placeholders);
    }
}
