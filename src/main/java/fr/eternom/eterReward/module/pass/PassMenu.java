package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterReward.module.pass.PassDefinition.Reward;
import fr.eternom.eterReward.module.pass.PassDefinition.Tier;
import fr.eternom.eterReward.module.pass.PassService.Season;
import fr.eternom.eterReward.module.pass.PassService.View;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu /pass, 6 lignes, 7 paliers par page :
 * <pre>
 *  ▣ ▣ ▢ ▢ ☺ ▢ ▢ ▣ ▣     ☺ = joueur (palier, XP, premium, temps restant, en direct)
 *  ▣ g g g g g g g ▣     g = récompense gratuite du palier
 *  ▢ n n n n n n n ▢     n = palier (vert : atteint, gris : à venir ; la pile = numéro)
 *  ▣ p p p p p p p ▣     p = récompense premium du palier
 *  ▣ ▣ ▢ ▢ ★ ▢ ▢ ▣ ▣     ★ = le pass du mois (thème, bonus, comment gagner de l'XP)
 *  ‹ ▢ ▢ ▢ « ▢ ▢ ▢ ›     ‹ › = pages, « = retour
 * </pre>
 */
class PassMenu implements Menu {

    static final int PER_PAGE = 7;
    private static final int INFO = 4;
    private static final int FREE_ROW = 10;
    private static final int TIER_ROW = 19;
    private static final int PREMIUM_ROW = 28;
    private static final int PASS_INFO = 40;
    private static final int PREVIOUS = 45;
    private static final int CHALLENGES = 47;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    private final PassService service;
    private final Player viewer;
    private final View view;
    private final int page;
    private final int pages;
    private final Inventory inventory;
    private final BukkitTask refresher;

    PassMenu(PassService service, Player viewer, View view, int page) {
        this.service = service;
        this.viewer = viewer;
        this.view = view;
        this.pages = (view.season().pass().tiers().size() + PER_PAGE - 1) / PER_PAGE;
        this.page = Math.clamp(page, 0, Math.max(0, pages - 1));
        this.inventory = Bukkit.createInventory(this, 54, text("pass.menu.title", TagResolver.resolver(
                Placeholder.component("pass", service.messages().render(view.season().pass().name(), TagResolver.empty())))));
        render();
        // Le temps restant change chaque seconde : seul l'item du joueur est redessiné
        refresher = Bukkit.getScheduler().runTaskTimer(service.plugin(), this::refreshInfo, 20, 20);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            service.backButton().click(player);
            return;
        }
        if (slot == CHALLENGES) {
            Sounds.click(player);
            service.openChallenges(player);
            return;
        }
        if (slot == PREVIOUS && page > 0 || slot == NEXT && page < pages - 1) {
            Sounds.page(player);
            player.openInventory(new PassMenu(service, viewer, view, page + (slot == NEXT ? 1 : -1)).getInventory());
            return;
        }
        boolean premium = slot >= PREMIUM_ROW && slot < PREMIUM_ROW + PER_PAGE;
        boolean free = slot >= FREE_ROW && slot < FREE_ROW + PER_PAGE;
        if (!premium && !free) {
            return;
        }
        int number = page * PER_PAGE + slot - (premium ? PREMIUM_ROW : FREE_ROW) + 1;
        if (number > view.season().pass().tiers().size()) {
            return;
        }
        if (state(number, premium) == State.READY) {
            Sounds.click(player);
            service.claim(player, view.season(), number, premium, page);
        } else {
            Sounds.refuse(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private enum State { READY, CLAIMED, LOCKED, PREMIUM, EMPTY }

    private State state(int number, boolean premium) {
        Tier tier = view.season().pass().tiers().get(number - 1);
        if ((premium ? tier.premium() : tier.free()) == null) {
            return State.EMPTY;
        }
        if (view.claimed().contains(number + (premium ? "p" : ""))) {
            return State.CLAIMED;
        }
        if (service.tier(view.season(), view.xp()) < number) {
            return State.LOCKED;
        }
        return premium && !view.premium() ? State.PREMIUM : State.READY;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        Season season = view.season();
        int reached = service.tier(season, view.xp());
        for (int i = 0; i < PER_PAGE; i++) {
            int number = page * PER_PAGE + i + 1;
            if (number > season.pass().tiers().size()) {
                break;
            }
            Tier tier = season.pass().tiers().get(number - 1);
            inventory.setItem(FREE_ROW + i, rewardItem(tier.free(), number, false));
            inventory.setItem(PREMIUM_ROW + i, rewardItem(tier.premium(), number, true));
            ItemStack marker = Items.item(number <= reached ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    text(number <= reached ? "pass.menu.tier-reached" : "pass.menu.tier-upcoming", "tier", String.valueOf(number)),
                    List.of(text("pass.menu.tier-xp", "xp", String.valueOf(number * service.settings().xpPerTier()))));
            marker.setAmount(number);
            inventory.setItem(TIER_ROW + i, marker);
        }
        inventory.setItem(PASS_INFO, passInfo());
        if (page > 0) {
            inventory.setItem(PREVIOUS, Items.item(Material.ARROW, text("pass.menu.previous"), List.of()));
        }
        if (page < pages - 1) {
            inventory.setItem(NEXT, Items.item(Material.ARROW, text("pass.menu.next"), List.of()));
        }
        inventory.setItem(CHALLENGES, Items.item(Material.TARGET, text("pass.menu.challenges"), List.of(text("pass.menu.challenges-lore"))));
        inventory.setItem(BACK, service.backButton().item(viewer));
        refreshInfo();
    }

    private ItemStack rewardItem(Reward reward, int number, boolean premium) {
        State state = state(number, premium);
        if (state == State.EMPTY) {
            return Items.pane(premium ? Material.PURPLE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        }
        List<Component> lore = new ArrayList<>();
        lore.add(service.messages().render(service.raw(viewer, "pass.menu.reward"),
                Placeholder.component("content", service.rewards().describe(viewer, reward))));
        lore.add(Component.empty());
        lore.add(text(switch (state) {
            case READY -> "pass.menu.claim";
            case CLAIMED -> "pass.menu.claimed";
            case LOCKED -> "pass.menu.locked";
            default -> "pass.menu.premium-locked";
        }, "tier", String.valueOf(number)));
        Material icon = state == State.CLAIMED ? Material.LIME_DYE : reward.icon();
        return Items.item(icon, text(premium ? "pass.menu.premium-name" : "pass.menu.free-name", "tier", String.valueOf(number)),
                lore, state == State.READY);
    }

    private ItemStack passInfo() {
        PassDefinition pass = view.season().pass();
        List<Component> lore = new ArrayList<>();
        pass.description().forEach(line -> lore.add(service.messages().render(line, TagResolver.empty())));
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        PassService.Settings settings = service.settings();
        int cap = pass.bonus().playtimeCap() > 0 ? pass.bonus().playtimeCap() : settings.playtimeCap();
        lore.add(text("pass.menu.how"));
        lore.add(text("pass.menu.how-playtime", "xp", String.valueOf(settings.playtimeXpPerMinute()), "cap", String.valueOf(cap)));
        lore.add(text("pass.menu.how-daily", "xp", String.valueOf(Math.round(settings.dailyXp() * pass.bonus().dailyMultiplier()))));
        lore.add(text("pass.menu.how-quests"));
        return Items.item(pass.icon(), service.messages().render(pass.name(), TagResolver.empty()), lore, true);
    }

    private void refreshInfo() {
        if (viewer.getOpenInventory().getTopInventory().getHolder(false) != this && refresher != null) {
            refresher.cancel(); // menu fermé
            return;
        }
        Season season = view.season();
        int reached = service.tier(season, view.xp());
        int max = season.pass().tiers().size();
        int perTier = service.settings().xpPerTier();
        List<Component> lore = new ArrayList<>();
        lore.add(text("pass.menu.tier", "tier", String.valueOf(reached), "max", String.valueOf(max)));
        lore.add(reached >= max
                ? text("pass.menu.complete")
                : text("pass.menu.xp", "xp", String.valueOf(view.xp() - reached * perTier), "needed", String.valueOf(perTier)));
        lore.add(text(view.premium() ? "pass.menu.premium-yes" : "pass.menu.premium-no"));
        lore.add(Component.empty());
        lore.add(text("pass.menu.ends", "time", service.timeLeft(viewer, season)));
        lore.add(text("pass.menu.rule"));
        inventory.setItem(INFO, Items.head(viewer.getPlayerProfile(), text("pass.menu.player", "player", viewer.getName()), lore));
    }

    private Component text(String key, String... placeholders) {
        return service.messages().get(viewer, key, placeholders);
    }

    private Component text(String key, TagResolver tags, String... placeholders) {
        return service.messages().get(viewer, key, tags, placeholders);
    }
}
