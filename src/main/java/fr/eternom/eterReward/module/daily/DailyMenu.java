package fr.eternom.eterReward.module.daily;

import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterReward.module.daily.DailyRewards.Day;
import fr.eternom.eterReward.module.daily.DailyRewards.Loot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Menu /daily, 5 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ☺ ▢ ▢ ▣ ▣     ☺ = joueur (série, temps avant le prochain jour, en direct)
 *  ▣ · · · · · · · ▣
 *  ▢ 1 2 3 4 5 6 7 ▢     jours du cycle : réclamé, aujourd'hui (brille, clic), à venir
 *  ▣ · · · · · · · ▣
 *  ▣ ▣ ▢ ▢ « ▢ ▢ ▣ ▣     « = retour (commande de la config) ou fermer
 * </pre>
 * Chaque jour montre ses tirages possibles et leur chance ; la pile de l'icône indique le numéro du jour.
 */
class DailyMenu implements Menu {

    private static final int INFO = 4;
    private static final int FIRST_DAY = 19;
    private static final int BACK = 40;
    private static final Set<Integer> ACCENT_FRAME = Set.of(0, 1, 7, 8, 9, 17, 27, 35, 36, 37, 43, 44);

    private final DailyService service;
    private final Messages messages;
    private final Player viewer;
    private final DailyState state;
    private final Inventory inventory;
    private final BukkitTask refresher;

    DailyMenu(DailyService service, Player viewer, DailyState state) {
        this.service = service;
        this.messages = service.messages();
        this.viewer = viewer;
        this.state = state;
        this.inventory = Bukkit.createInventory(this, 45, text("daily.menu.title"));
        render();
        // Le temps avant le prochain jour change chaque seconde : seul l'item du joueur est redessiné
        refresher = Bukkit.getScheduler().runTaskTimer(service.plugin(), this::refreshInfo, 20, 20);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            service.backButton().click(player);
            return;
        }
        int day = slot - FIRST_DAY + 1;
        if (day < 1 || day > DailyState.CYCLE) {
            return;
        }
        if (day == state.claimable()) {
            Sounds.click(player);
            player.closeInventory();
            service.claim(player, state);
        } else {
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        ItemStack accent = Items.pane(Material.ORANGE_STAINED_GLASS_PANE);
        ItemStack neutral = Items.pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == 4 || column == 0 || column == 8) {
                inventory.setItem(slot, ACCENT_FRAME.contains(slot) ? accent : neutral);
            }
        }
        for (int day = 1; day <= DailyState.CYCLE; day++) {
            inventory.setItem(FIRST_DAY + day - 1, dayItem(service.rewards().day(day)));
        }
        inventory.setItem(BACK, service.backButton().item(viewer));
        refreshInfo();
    }

    private ItemStack dayItem(Day day) {
        int number = day.number();
        boolean claimed = number <= state.claimed();
        boolean today = number == state.claimable();
        boolean last = number == DailyState.CYCLE;

        List<Component> lore = new ArrayList<>();
        lore.add(text("daily.day.loots"));
        int total = day.totalWeight();
        for (Loot loot : day.loots()) {
            String chance = String.valueOf(Math.round(loot.weight() * 100.0 / total));
            lore.add(messages.render(raw("daily.loot.line"), Placeholder.component("content", service.describe(viewer, loot)),
                    "chance", chance));
        }
        lore.add(Component.empty());
        lore.add(text(claimed ? "daily.day.claimed" : today ? "daily.day.claim" : "daily.day.upcoming"));

        Material icon = claimed ? Material.LIME_DYE : day.icon();
        ItemStack item = Items.item(icon, text(last ? "daily.day.name-last" : "daily.day.name", "day", String.valueOf(number)),
                lore, today);
        item.setAmount(number);
        return item;
    }

    private void refreshInfo() {
        if (viewer.getOpenInventory().getTopInventory().getHolder(false) != this && refresher != null) {
            refresher.cancel(); // menu fermé
            return;
        }
        List<Component> lore = new ArrayList<>();
        lore.add(text("daily.menu.streak", "days", String.valueOf(state.claimed()), "cycle", String.valueOf(DailyState.CYCLE)));
        lore.add(Component.empty());
        lore.add(state.canClaim()
                ? text("daily.menu.ready")
                : text("daily.menu.next", "time", service.timeUntilTomorrow(viewer)));
        lore.add(text("daily.menu.rule"));
        inventory.setItem(INFO, Items.head(viewer.getPlayerProfile(), text("daily.menu.player", "player", viewer.getName()), lore));
    }

    private String raw(String key) {
        String raw = messages.raw(viewer, key);
        return raw == null ? key : raw;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
