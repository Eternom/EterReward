package fr.eternom.eterReward.module.pass;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.module.pass.PassService.Season;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Le premium du pass, par MOIS (et non par pass : changer le pass du mois ne le retire à personne) :
 * - boutique et staff : /eterreward pass premium <joueur> [acheteur] [paliers] (cadeau si acheteur, paliers sautés) ;
 *   acheté dans les pass.premium.late-buy-days derniers jours, le mois suivant est compris ;
 * - en jeu, contre de l'argent (pass.premium.price, 0 : désactivé), pour soi ou pour tout son clan (réduction) ;
 * - permission eterreward.pass.premium.always (grade) : tous les mois.
 * Chaque premium donné en base vaut aussi un insigne du mois (objet de collection), dans la boîte.
 */
final class PassPremium {

    private final PassService service;

    PassPremium(PassService service) {
        this.service = service;
    }

    /** Les mois couverts par un premium donné maintenant : celui en cours, et le suivant en fin de mois. */
    private List<String> monthsNow(Season season) {
        List<String> months = new ArrayList<>(List.of(season.key()));
        if (Duration.between(service.now(), season.end()).toDays() < service.settings().lateBuyDays()) {
            months.add(YearMonth.parse(season.key()).plusMonths(1).format(PassService.SEASON_FORMAT));
        }
        return months;
    }

    /** Donne le premium (base) pour ces mois, avec l'insigne de chacun ; true si au moins un est nouveau. Bloquant. */
    private boolean grant(UUID player, Season season, List<String> months, String source) {
        boolean any = false;
        for (String month : months) {
            if (service.store().givePremium(player, month, source)) {
                any = true;
                if (service.settings().badge()) {
                    String pass = month.equals(season.key()) ? season.pass().id() : "";
                    service.store().addParcel(player, new Parcel(0, month, "badge", 0, 0, 0, List.of(), List.of(), List.of(),
                            "badge:" + month + ":" + pass));
                }
            }
        }
        return any;
    }

    /**
     * Boutique, staff, console : le premium pour target (connecté ou non, n'importe où). from : l'acheteur s'il l'offre
     * (vide sinon) ; skip : paliers sautés en plus (offre « premium + paliers »). Thread principal.
     */
    void give(CommandSender sender, String targetName, String from, int skip) {
        Messages messages = service.messages();
        Tasks.async(service.plugin(), () -> {
            var target = EterLib.get().getPlayers().find(targetName);
            if (target.isEmpty()) {
                Bukkit.getScheduler().runTask(service.plugin(), () -> messages.send(sender, "player.unknown", "player", targetName));
                return;
            }
            UUID uuid = target.get().uuid();
            Season season = service.season();
            List<String> months = monthsNow(season);
            grant(uuid, season, months, from.isEmpty() ? "shop" : "gift:" + from);
            if (skip > 0) {
                int xp = service.store().xp(uuid, season.key());
                service.addXp(uuid, season, service.curve().xpToSkip(xp, skip), PassService.Source.SHOP);
            }
            int ready = service.claimableLines(season, service.store().xp(uuid, season.key()),
                    service.store().claims(uuid, season.key()), true).stream().filter(PassService.Line::premium).toList().size();
            boolean late = months.size() > 1;
            Bukkit.getScheduler().runTask(service.plugin(), () -> {
                messages.send(sender, "pass.premium-given", "player", target.get().name(), "pass", season.key());
                if (!from.isEmpty()) {
                    service.bus().notify(uuid, "pass.premium-gift", true, "from", from);
                } else {
                    service.bus().notify(uuid, "pass.premium-received", true);
                }
                if (late) {
                    service.bus().notify(uuid, "pass.premium-next-month", false);
                }
                if (skip > 0) {
                    service.bus().notify(uuid, "pass.premium-skip", false, "count", String.valueOf(skip));
                }
                if (ready > 0) {
                    service.bus().notify(uuid, "pass.premium-claimable", false, "count", String.valueOf(ready));
                }
                if (service.settings().badge()) {
                    service.bus().notify(uuid, "pass.box.badge", false);
                }
            });
        }, "Premium du pass non donné à " + targetName);
    }

    /** Achat du premium en jeu (shift-clic dans le menu). Thread principal. */
    void buy(Player player) {
        PassSettings settings = service.settings();
        Messages messages = service.messages();
        if (settings.premiumPrice() <= 0) {
            shopLink(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        Set<String> permissions = service.premiumPermissions(player);
        Tasks.async(service.plugin(), player, () -> {
            Season season = service.season();
            if (service.isPremium(uuid, permissions, season)) {
                return "pass.premium-already";
            }
            EconomyApi economy = EconomyApi.get().orElse(null);
            if (economy == null || !economy.withdraw(uuid, settings.premiumPrice(), "EterReward · pass premium")) {
                return "pass.premium-poor";
            }
            if (!grant(uuid, season, monthsNow(season), "game")) {
                economy.deposit(uuid, settings.premiumPrice(), "EterReward · pass premium (remboursé)");
                return "pass.premium-already";
            }
            return null;
        }, error -> {
            if (error != null) {
                messages.send(player, error, "price", Money.format(settings.premiumPrice()));
                Sounds.refuse(player);
                return;
            }
            messages.send(player, "pass.premium-bought", "price", Money.format(settings.premiumPrice()));
            if (settings.badge()) {
                service.boxButton(player, "pass.box.badge");
            }
            player.playSound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            service.open(player, PassService.Which.CURRENT, -1, false);
        }, () -> messages.send(player, "error.generic"));
    }

    /**
     * Le chef de clan offre le premium à tous les membres qui ne l'ont pas encore, avec la réduction de clan. Seulement
     * à partir de pass.premium.clan-min-members membres. Thread principal.
     */
    void buyForClan(Player player) {
        PassSettings settings = service.settings();
        Messages messages = service.messages();
        if (settings.premiumPrice() <= 0) {
            shopLink(player);
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("EterClan")) {
            messages.send(player, "pass.clan.none");
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Tasks.async(service.plugin(), player, () -> {
            ClanGoals.Clan clan = ClanGoals.clanOf(uuid);
            if (clan == null) {
                return new Object[]{"pass.clan.none"};
            }
            if (!clan.owner().equals(uuid)) {
                return new Object[]{"pass.clan.owner-only"};
            }
            if (clan.members().size() < settings.clanMinMembers()) {
                return new Object[]{"pass.clan.too-small", String.valueOf(settings.clanMinMembers())};
            }
            Season season = service.season();
            List<UUID> lacking = clan.members().stream().filter(member -> !service.store().hasPremium(member, season.key())).toList();
            if (lacking.isEmpty()) {
                return new Object[]{"pass.clan.all-premium"};
            }
            double price = Math.round(settings.premiumPrice() * lacking.size() * (1 - settings.clanDiscount()));
            EconomyApi economy = EconomyApi.get().orElse(null);
            if (economy == null || !economy.withdraw(uuid, price, "EterReward · pass premium du clan")) {
                return new Object[]{"pass.premium-poor", Money.format(price)};
            }
            List<String> months = monthsNow(season);
            int given = 0;
            for (UUID member : lacking) {
                if (grant(member, season, months, "clan:" + name)) {
                    given++;
                }
            }
            if (given < lacking.size()) {
                // Quelqu'un l'a eu entre-temps : sa part est rendue
                economy.deposit(uuid, Math.round(settings.premiumPrice() * (lacking.size() - given) * (1 - settings.clanDiscount())),
                        "EterReward · pass premium du clan (remboursé)");
            }
            return new Object[]{null, lacking, given, price};
        }, result -> {
            if (result[0] != null) {
                messages.send(player, (String) result[0], "min", result.length > 1 ? (String) result[1] : "",
                        "price", result.length > 1 ? (String) result[1] : "");
                Sounds.refuse(player);
                return;
            }
            @SuppressWarnings("unchecked") List<UUID> members = (List<UUID>) result[1];
            messages.send(player, "pass.clan.bought", "count", String.valueOf(result[2]), "price", Money.format((double) result[3]));
            for (UUID member : members) {
                if (!member.equals(uuid)) {
                    service.bus().notify(member, "pass.premium-gift", true, "from", name);
                }
            }
            player.playSound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            service.open(player, PassService.Which.CURRENT, -1, false);
        }, () -> messages.send(player, "error.generic"));
    }

    /** Le lien de la boutique, cliquable. */
    void shopLink(Player player) {
        Messages messages = service.messages();
        String url = service.settings().shopUrl();
        if (url.isEmpty()) {
            messages.send(player, "pass.premium-shop-none");
            return;
        }
        Component link = messages.get(player, "pass.premium-shop-link").clickEvent(ClickEvent.openUrl(url));
        player.sendMessage(messages.prefix().append(messages.get(player, "pass.premium-shop")).append(Component.space()).append(link));
    }

    /** L'insigne du premium d'un mois ("badge:2026-11:chasse"), objet de collection. */
    ItemStack badge(Player player, String special) {
        String[] parts = special.split(":", 3);
        if (parts.length < 2) {
            return null;
        }
        String month = parts[1];
        PassDefinition pass = parts.length > 2 && !parts[2].isEmpty() ? service.passById(parts[2]) : null;
        Messages messages = service.messages();
        Component passName = pass == null ? messages.get(player, "pass.badge.unknown-pass")
                : messages.render(pass.name(), TagResolver.empty());
        ItemStack item = Items.item(pass == null ? Material.NETHER_STAR : pass.icon(),
                messages.get(player, "pass.badge.name", "month", month).append(Component.space()).append(passName),
                List.of(messages.get(player, "pass.badge.lore", "month", month), messages.get(player, "pass.badge.owner", "player", player.getName())),
                true);
        item.setAmount(1);
        return item;
    }
}
