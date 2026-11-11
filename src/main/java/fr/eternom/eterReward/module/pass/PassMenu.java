package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Dialogs;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterReward.module.pass.PassDefinition.Reward;
import fr.eternom.eterReward.module.pass.PassDefinition.Tier;
import fr.eternom.eterReward.module.pass.PassService.Line;
import fr.eternom.eterReward.module.pass.PassService.Season;
import fr.eternom.eterReward.module.pass.PassService.View;
import fr.eternom.eterReward.module.pass.PassService.Which;
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
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Menu /pass, 6 lignes, 7 paliers par page :
 * <pre>
 *  ▣ ▣ ✉ ▢ ☺ ▢ ♛ ▣ ▣     ✉ = boîte de colis · ☺ = joueur (palier, barre, plafond du jour, fin) · ♛ = premium
 *  ▣ g g g g g g g ▣     g = récompense gratuite (clic : réclamer, clic droit : détail)
 *  ▢ n n n n n n n ▢     n = palier (vert : atteint, gris : à venir ; la pile = numéro)
 *  ▣ p p p p p p p ▣     p = récompense premium
 *  ▣ ⇊ ⚲ ▢ ★ ◷ ✚ ⌛ ▣     ⇊ tout réclamer · ⚲ « à réclamer seulement » · ★ gagner de l'XP · ◷ aperçu du pass suivant
 *  ‹ ▢ ⚔ ▢ « ▢ ☰ ▢ ›     ✚ paliers bonus · ⌛ mois d'avant · ⚔ défis · « retour · ☰ historique · ‹ › pages
 * </pre>
 * La page s'ouvre sur le premier palier à réclamer. Le mois d'avant (délai de grâce) et l'aperçu du suivant
 * s'ouvrent dans le même menu (l'aperçu en lecture seule).
 */
class PassMenu implements Menu {

    static final int PER_PAGE = 7;
    private static final int BOX = 2;
    private static final int INFO = 4;
    private static final int PREMIUM = 6;
    private static final int FREE_ROW = 10;
    private static final int TIER_ROW = 19;
    private static final int PREMIUM_ROW = 28;
    private static final int CLAIM_ALL = 37;
    private static final int FILTER = 38;
    private static final int HOW = 40;
    private static final int PREVIEW = 41;
    private static final int OVERFLOW = 42;
    private static final int OTHER = 43;
    private static final int PREVIOUS = 45;
    private static final int CHALLENGES = 47;
    private static final int BACK = 49;
    private static final int HISTORY = 51;
    private static final int NEXT = 53;
    /** Moins de 3 jours : la fin du pass s'affiche en rouge. */
    private static final long SOON_SECONDS = 3 * 86_400;

    private final PassService service;
    private final Player viewer;
    private final View view;
    private final boolean filter;
    /** Les paliers montrés (tous, ou ceux à réclamer avec le filtre). */
    private final List<Integer> numbers;
    private final List<Line> claimable;
    private final int page;
    private final int pages;
    private final Inventory inventory;
    private final BukkitTask refresher;

    PassMenu(PassService service, Player viewer, View view, int page, boolean filter) {
        this.service = service;
        this.viewer = viewer;
        this.view = view;
        this.filter = filter;
        int tiers = view.season().pass().tiers().size();
        this.claimable = view.readOnly() ? List.of() : service.claimableLines(view.season(), view.xp(), view.claimed(), view.premium());
        this.numbers = filter
                ? claimable.stream().map(Line::tier).filter(tier -> tier <= tiers).distinct().sorted().toList()
                : IntStream.rangeClosed(1, tiers).boxed().toList();
        this.pages = Math.max(1, (numbers.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.clamp(page < 0 ? startPage(tiers) : page, 0, pages - 1);
        Component title = text(view.which() == Which.CURRENT ? "pass.menu.title" : view.which() == Which.NEXT
                ? "pass.menu.title-next" : "pass.menu.title-previous", TagResolver.resolver(
                Placeholder.component("pass", service.messages().render(view.season().pass().name(), TagResolver.empty()))));
        this.inventory = Bukkit.createInventory(this, 54, title);
        render();
        // Le temps restant change chaque seconde : seul l'item du joueur est redessiné
        refresher = Bukkit.getScheduler().runTaskTimer(service.plugin(), this::refreshInfo, 20, 20);
    }

    /** Page du premier palier à réclamer ; sinon celle du palier en cours. */
    private int startPage(int tiers) {
        int target = claimable.stream().map(Line::tier).filter(tier -> tier <= tiers).findFirst()
                .orElse(Math.min(tiers, service.tier(view.xp()) + 1));
        int index = numbers.indexOf(target);
        return Math.max(0, index) / PER_PAGE;
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case BACK -> {
                if (view.which() == Which.CURRENT) {
                    service.backButton().click(player);
                } else {
                    Sounds.page(player);
                    service.open(player, Which.CURRENT, -1, false);
                }
                return;
            }
            case CHALLENGES -> {
                Sounds.click(player);
                service.openChallenges(player);
                return;
            }
            case BOX -> {
                Sounds.page(player);
                service.openBox(player);
                return;
            }
            case HISTORY -> {
                Sounds.click(player);
                player.closeInventory();
                service.history(player);
                return;
            }
            case PREMIUM -> {
                if (view.premium() || view.readOnly()) {
                    return;
                }
                Sounds.click(player);
                if (click == ClickType.SHIFT_LEFT && service.settings().premiumPrice() > 0) {
                    confirmPremium(player, false);
                } else if (click == ClickType.SHIFT_RIGHT && service.settings().premiumPrice() > 0) {
                    confirmPremium(player, true);
                } else {
                    player.closeInventory();
                    service.premium().shopLink(player);
                }
                return;
            }
            case CLAIM_ALL -> {
                if (view.readOnly() || claimable.isEmpty()) {
                    Sounds.refuse(player);
                } else {
                    Sounds.click(player);
                    service.claim(player, view.which(), view.season(), List.of(), page, filter);
                }
                return;
            }
            case FILTER -> {
                if (!view.readOnly()) {
                    Sounds.page(player);
                    player.openInventory(new PassMenu(service, viewer, view, filter ? -1 : 0, !filter).getInventory());
                }
                return;
            }
            case OVERFLOW -> {
                List<Line> bonus = claimable.stream().filter(line -> line.tier() > view.season().pass().tiers().size()).toList();
                if (view.readOnly() || bonus.isEmpty()) {
                    Sounds.refuse(player);
                } else {
                    Sounds.click(player);
                    service.claim(player, view.which(), view.season(), bonus, page, filter);
                }
                return;
            }
            case PREVIEW -> {
                if (view.preview()) {
                    Sounds.page(player);
                    service.open(player, Which.NEXT, 0, false);
                }
                return;
            }
            case OTHER -> {
                if (view.which() == Which.CURRENT && view.previousClaimable() > 0) {
                    Sounds.page(player);
                    service.open(player, Which.PREVIOUS, -1, false);
                }
                return;
            }
            default -> {
            }
        }
        if (slot == PREVIOUS && page > 0 || slot == NEXT && page < pages - 1) {
            Sounds.page(player);
            player.openInventory(new PassMenu(service, viewer, view, page + (slot == NEXT ? 1 : -1), filter).getInventory());
            return;
        }
        boolean premium = slot >= PREMIUM_ROW && slot < PREMIUM_ROW + PER_PAGE;
        boolean free = slot >= FREE_ROW && slot < FREE_ROW + PER_PAGE;
        if (!premium && !free) {
            return;
        }
        int index = page * PER_PAGE + slot - (premium ? PREMIUM_ROW : FREE_ROW);
        if (index >= numbers.size()) {
            return;
        }
        int number = numbers.get(index);
        Tier tier = view.season().pass().tiers().get(number - 1);
        Reward reward = premium ? tier.premium() : tier.free();
        if (click.isRightClick() && reward != null) {
            // Le détail ligne par ligne, dans le chat
            Sounds.click(player);
            service.messages().send(player, premium ? "pass.details.title-premium" : "pass.details.title", "tier", String.valueOf(number));
            service.rewards().details(player, reward).forEach(player::sendMessage);
            return;
        }
        if (state(number, premium) == State.READY) {
            Sounds.click(player);
            service.claim(player, view.which(), view.season(), List.of(new Line(number, premium)), page, filter);
        } else {
            Sounds.refuse(player);
        }
    }

    /**
     * Achat du premium (Maj + clic) : une confirmation commune (Dialogs) avec le prix avant de payer ; Annuler rouvre le
     * pass. clan : pour les membres du clan qui ne l'ont pas, avec la remise du clan.
     */
    private void confirmPremium(Player player, boolean clan) {
        player.closeInventory();
        PassSettings settings = service.settings();
        var messages = service.messages();
        DialogBase base = DialogBase.builder(messages.get(player, "pass.premium-confirm.title"))
                .body(List.of(DialogBody.plainMessage(clan
                        ? messages.get(player, "pass.premium-confirm.body-clan", "price", Money.format(settings.premiumPrice()),
                        "discount", String.valueOf(Math.round(settings.clanDiscount() * 100)))
                        : messages.get(player, "pass.premium-confirm.body", "price", Money.format(settings.premiumPrice())))))
                .build();
        Dialogs.show(service.plugin(), player, base, messages.get(player, "pass.premium-confirm.confirm"),
                messages.get(player, "dialog.cancel"), response -> {
                    if (clan) {
                        service.premium().buyForClan(player);
                    } else {
                        service.premium().buy(player);
                    }
                }, () -> service.open(player, Which.CURRENT, -1, false));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private enum State { READY, CLAIMED, LOCKED, PREMIUM, EMPTY, PREVIEW }

    private State state(int number, boolean premium) {
        Tier tier = view.season().pass().tiers().get(number - 1);
        if ((premium ? tier.premium() : tier.free()) == null) {
            return State.EMPTY;
        }
        if (view.readOnly()) {
            return State.PREVIEW;
        }
        if (view.claimed().contains(number + (premium ? "p" : ""))) {
            return State.CLAIMED;
        }
        if (service.tier(view.xp()) < number) {
            return State.LOCKED;
        }
        return premium && !view.premium() ? State.PREMIUM : State.READY;
    }

    private void render() {
        Frame.draw(inventory, view.readOnly() ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE);
        int reached = service.tier(view.xp());
        XpCurve curve = service.curve();
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= numbers.size()) {
                break;
            }
            int number = numbers.get(index);
            Tier tier = view.season().pass().tiers().get(number - 1);
            inventory.setItem(FREE_ROW + i, rewardItem(tier.free(), number, false));
            inventory.setItem(PREMIUM_ROW + i, rewardItem(tier.premium(), number, true));
            ItemStack marker = Items.item(number <= reached ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    text(number <= reached ? "pass.menu.tier-reached" : "pass.menu.tier-upcoming", "tier", String.valueOf(number)),
                    List.of(text("pass.menu.tier-xp", "xp", String.valueOf(curve.threshold(number)))));
            marker.setAmount(number);
            inventory.setItem(TIER_ROW + i, marker);
        }
        if (filter && numbers.isEmpty()) {
            inventory.setItem(TIER_ROW + PER_PAGE / 2, Items.item(Material.LIME_DYE, text("pass.menu.filter-empty"), List.of()));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, Items.item(Material.ARROW, text("pass.menu.previous"), List.of()));
        }
        if (page < pages - 1) {
            inventory.setItem(NEXT, Items.item(Material.ARROW, text("pass.menu.next"), List.of()));
        }
        inventory.setItem(HOW, howItem());
        inventory.setItem(CHALLENGES, Items.item(Material.TARGET, text("pass.menu.challenges"), List.of(text("pass.menu.challenges-lore"))));
        inventory.setItem(BACK, view.which() == Which.CURRENT ? service.backButton().item(viewer)
                : Items.item(Material.OAK_DOOR, text("pass.menu.back-current"), List.of()));
        if (!view.readOnly()) {
            inventory.setItem(BOX, Items.item(view.parcels() > 0 ? Material.CHEST_MINECART : Material.MINECART, text("pass.menu.box"),
                    List.of(text(view.parcels() > 0 ? "pass.menu.box-lore" : "pass.menu.box-empty", "count", String.valueOf(view.parcels()))),
                    view.parcels() > 0));
            inventory.setItem(PREMIUM, premiumItem());
            inventory.setItem(CLAIM_ALL, Items.item(claimable.isEmpty() ? Material.GRAY_DYE : Material.HOPPER, text("pass.menu.claim-all"),
                    List.of(text(claimable.isEmpty() ? "pass.menu.claim-all-none" : "pass.menu.claim-all-lore",
                            "count", String.valueOf(claimable.size()))), !claimable.isEmpty()));
            inventory.setItem(FILTER, Items.item(filter ? Material.ENDER_EYE : Material.ENDER_PEARL, text("pass.menu.filter"),
                    List.of(text(filter ? "pass.menu.filter-on" : "pass.menu.filter-off"))));
            inventory.setItem(OVERFLOW, overflowItem());
            inventory.setItem(HISTORY, Items.item(Material.BOOK, text("pass.menu.history"), List.of(text("pass.menu.history-lore"))));
        }
        if (view.preview()) {
            inventory.setItem(PREVIEW, Items.item(Material.SPYGLASS, text("pass.menu.preview"), List.of(text("pass.menu.preview-lore"))));
        }
        if (view.which() == Which.CURRENT && view.previousClaimable() > 0) {
            inventory.setItem(OTHER, Items.item(Material.CLOCK, text("pass.menu.previous-season"),
                    List.of(text("pass.menu.previous-season-lore", "count", String.valueOf(view.previousClaimable()),
                            "days", String.valueOf(service.settings().graceDays()))), true));
        }
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
            case PREVIEW -> "pass.menu.preview-only";
            default -> "pass.menu.premium-locked";
        }, "tier", String.valueOf(number)));
        if (state == State.PREMIUM) {
            lore.add(text("pass.menu.premium-get"));
        }
        lore.add(text("pass.menu.details"));
        Material icon = state == State.CLAIMED ? Material.LIME_DYE : reward.icon();
        // Premium pas encore débloqué : brillant, pour donner envie
        boolean glint = state == State.READY || state == State.PREMIUM;
        return Items.item(icon, text(premium ? "pass.menu.premium-name" : "pass.menu.free-name", "tier", String.valueOf(number)),
                lore, glint);
    }

    private ItemStack premiumItem() {
        PassSettings settings = service.settings();
        if (view.premium()) {
            return Items.item(Material.GOLDEN_HELMET, text("pass.menu.premium-active"), List.of(text("pass.menu.premium-active-lore")), true);
        }
        List<Component> lore = new ArrayList<>();
        lore.add(text("pass.menu.premium-lore"));
        lore.add(Component.empty());
        if (!settings.shopUrl().isEmpty()) {
            lore.add(text("pass.menu.premium-shop"));
        }
        if (settings.premiumPrice() > 0) {
            lore.add(text("pass.menu.premium-buy", "price", Money.format(settings.premiumPrice())));
            lore.add(text("pass.menu.premium-buy-clan", "discount", String.valueOf(Math.round(settings.clanDiscount() * 100)),
                    "min", String.valueOf(settings.clanMinMembers())));
        }
        if (service.secondsLeft(view.season()) < settings.lateBuyDays() * 86_400L) {
            lore.add(text("pass.menu.premium-late"));
        }
        return Items.item(Material.GOLDEN_HELMET, text("pass.menu.premium-button"), lore, true);
    }

    private ItemStack overflowItem() {
        XpCurve curve = service.curve();
        int tiers = curve.tiers();
        if (curve.overflowMax() <= 0) {
            return Items.pane(Material.ORANGE_STAINED_GLASS_PANE);
        }
        int reached = curve.overflow(view.xp());
        long ready = claimable.stream().filter(line -> line.tier() > tiers).count();
        Reward reward = service.overflowReward();
        List<Component> lore = new ArrayList<>();
        lore.add(text("pass.menu.overflow-lore", "xp", String.valueOf(curve.threshold(tiers + 1) - curve.threshold(tiers))));
        lore.add(service.messages().render(service.raw(viewer, "pass.menu.reward"),
                Placeholder.component("content", service.rewards().describe(viewer, reward))));
        lore.add(text("pass.menu.overflow-count", "count", String.valueOf(reached), "max", String.valueOf(curve.overflowMax())));
        lore.add(Component.empty());
        lore.add(text(ready > 0 ? "pass.menu.overflow-ready" : service.tier(view.xp()) < tiers ? "pass.menu.overflow-locked"
                : "pass.menu.overflow-none", "count", String.valueOf(ready)));
        return Items.item(Material.EXPERIENCE_BOTTLE, text("pass.menu.overflow"), lore, ready > 0);
    }

    /** « Gagner de l'XP » avec les chiffres réels du mois. */
    private ItemStack howItem() {
        PassDefinition pass = view.season().pass();
        PassSettings settings = service.settings();
        List<Component> lore = new ArrayList<>();
        pass.description().forEach(line -> lore.add(service.messages().render(line, TagResolver.empty())));
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        int cap = service.baseCap(view.season());
        lore.add(text("pass.menu.how"));
        lore.add(text("pass.menu.how-playtime", "xp", String.valueOf(settings.playtimeXpPerMinute()), "cap", String.valueOf(cap)));
        if (!settings.capDays().isEmpty()) {
            lore.add(text("pass.menu.how-cap-days", "days", String.join(", ", settings.capDays().keySet().stream()
                    .map(day -> service.messages().plain(viewer, "pass.day." + day.name().toLowerCase(Locale.ROOT))).toList())));
        }
        if (settings.carryOver() > 0) {
            lore.add(text("pass.menu.how-carry", "percent", String.valueOf(Math.round(settings.carryOver() * 100))));
        }
        lore.add(text("pass.menu.how-daily", "xp", String.valueOf(Math.round(settings.dailyXp() * pass.bonus().dailyMultiplier()))));
        lore.add(text("pass.menu.how-quests", "easy", String.valueOf(settings.questXp("easy")), "normal", String.valueOf(settings.questXp("normal")),
                "hard", String.valueOf(settings.questXp("hard"))));
        if (pass.bonus().questMultiplier() != 1) {
            lore.add(text(pass.bonus().questJob().isEmpty() ? "pass.menu.how-quest-bonus-all" : "pass.menu.how-quest-bonus",
                    "job", pass.bonus().questJob(), "multiplier", PassAnnouncer.multiplier(pass.bonus().questMultiplier())));
        }
        lore.add(text("pass.menu.how-challenges", "multiplier", PassAnnouncer.multiplier(pass.bonus().challengeMultiplier())));
        lore.add(text("pass.menu.how-votes", "multiplier", PassAnnouncer.multiplier(pass.bonus().voteMultiplier())));
        if (settings.successXp() > 0) {
            lore.add(text("pass.menu.how-success", "xp", PassAnnouncer.multiplier(settings.successXp())));
        }
        if (!settings.clanGoals().isEmpty()) {
            lore.add(text("pass.menu.how-clan"));
        }
        return Items.item(pass.icon(), service.messages().render(pass.name(), TagResolver.empty()), lore, true);
    }

    private void refreshInfo() {
        if (viewer.getOpenInventory().getTopInventory().getHolder(false) != this && refresher != null) {
            refresher.cancel(); // menu fermé
            return;
        }
        Season season = view.season();
        XpCurve curve = service.curve();
        int reached = curve.tier(view.xp());
        int max = curve.tiers();
        List<Component> lore = new ArrayList<>();
        lore.add(text("pass.menu.tier", "tier", String.valueOf(reached), "max", String.valueOf(max)));
        int[] progress = curve.progress(view.xp());
        if (progress[1] == 0) {
            lore.add(text("pass.menu.complete"));
        } else {
            lore.add(text("pass.menu.xp", "xp", String.valueOf(progress[0]), "needed", String.valueOf(progress[1])));
            lore.add(text("pass.menu.bar", TagResolver.resolver(Placeholder.component("bar", bar(progress[0], progress[1]))), "percent", String.valueOf(progress[0] * 100 / progress[1])));
        }
        if (!view.readOnly()) {
            lore.add(text(view.premium() ? "pass.menu.premium-yes" : "pass.menu.premium-no"));
        }
        if (view.which() == Which.CURRENT) {
            lore.add(text(view.playedToday() >= view.capToday() ? "pass.menu.cap-reached" : "pass.menu.cap",
                    "xp", String.valueOf(view.playedToday()), "cap", String.valueOf(view.capToday())));
            if (view.catchUp() > 1) {
                lore.add(text("pass.menu.catch-up", "multiplier", PassAnnouncer.multiplier(view.catchUp())));
            }
            if (view.boost() > 0) {
                lore.add(text("pass.menu.boost", "percent", String.valueOf(view.boost())));
            }
            if (view.event() > 1) {
                lore.add(text("pass.menu.event", "multiplier", PassAnnouncer.multiplier(view.event())));
            }
        }
        lore.add(Component.empty());
        long left = service.secondsLeft(season);
        switch (view.which()) {
            case CURRENT -> {
                lore.add(text(left < SOON_SECONDS ? "pass.menu.ends-soon" : "pass.menu.ends", "time", service.timeLeft(viewer, season)));
                lore.add(text("pass.menu.rule", "days", String.valueOf(service.settings().graceDays())));
            }
            case PREVIOUS -> lore.add(text("pass.menu.grace", "days", String.valueOf(service.settings().graceDays())));
            case NEXT -> lore.add(text("pass.menu.starts", "time", service.timeLeft(viewer, new Season(season.key(), season.pass(),
                    season.start(), season.start()))));
        }
        inventory.setItem(INFO, Items.head(viewer.getPlayerProfile(), text("pass.menu.player", "player", viewer.getName()), lore));
    }

    /** Barre de 20 cases. */
    private static Component bar(int done, int total) {
        int filled = total <= 0 ? 20 : Math.min(20, done * 20 / total);
        return Component.text("|".repeat(filled), net.kyori.adventure.text.format.NamedTextColor.GREEN)
                .append(Component.text("|".repeat(20 - filled), net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
    }

    private Component text(String key, String... placeholders) {
        return service.messages().get(viewer, key, placeholders);
    }

    private Component text(String key, TagResolver tags, String... placeholders) {
        return service.messages().get(viewer, key, tags, placeholders);
    }
}
