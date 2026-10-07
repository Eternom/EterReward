package fr.eternom.eterReward.module.daily;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.module.daily.DailyRewards.Day;
import fr.eternom.eterReward.module.daily.DailyRewards.Loot;
import fr.eternom.eterReward.module.daily.DailyRewards.Reward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Récompense quotidienne : une par jour et par joueur sur tout le réseau, série de 7 jours qui repart au jour 1 si un
 * jour est manqué. Le jour change à minuit dans le fuseau de la config (le même pour tout le monde).
 *
 * Réclamer : la base enregistre d'abord (une seule réclamation possible, même sur deux serveurs à la fois), puis le
 * tirage est donné. Objets qui ne rentrent pas : posés au sol devant le joueur. Argent : par Vault, en tâche de fond.
 */
public class DailyService {

    public static final String PERMISSION = "eterreward.daily";

    private final JavaPlugin plugin;
    private final DailyStore store;
    private final DailyRewards rewards;
    private final Messages messages;
    private final ZoneId zone;
    private final BackButton backButton;

    public DailyService(JavaPlugin plugin, DailyStore store, DailyRewards rewards, Messages messages, ZoneId zone,
                        BackButton backButton) {
        this.plugin = plugin;
        this.store = store;
        this.rewards = rewards;
        this.messages = messages;
        this.zone = zone;
        this.backButton = backButton;
    }

    /** /daily : ouvre le menu avec l'état du joueur. */
    public void open(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> DailyState.of(store.get(uuid), today()),
                state -> player.openInventory(new DailyMenu(this, player, state).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    /** À l'arrivée : prévient si la récompense du jour attend, avec un bouton pour ouvrir le menu. */
    public void remind(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> DailyState.of(store.get(uuid), today()), state -> {
            if (!state.canClaim()) {
                return;
            }
            messages.send(player, state.streakLost(today()) ? "daily.available-reset" : "daily.available",
                    "day", String.valueOf(state.claimable()));
            Component button = messages.get(player, "daily.button")
                    .clickEvent(ClickEvent.runCommand("/daily"))
                    .hoverEvent(HoverEvent.showText(messages.get(player, "daily.button-hover")));
            player.sendMessage(messages.prefix().append(button));
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f);
        }, () -> {
        });
    }

    /** Clic sur le jour du jour dans le menu. */
    void claim(Player player, DailyState shown) {
        Day day = rewards.day(shown.claimable());
        if (day.givesMoney() && Money.economy() == null) {
            messages.send(player, "daily.economy-missing");
            return;
        }
        UUID uuid = player.getUniqueId();
        long today = today();
        Tasks.async(plugin, player, () -> {
            DailyState state = DailyState.of(store.get(uuid), today);
            return state.canClaim() && store.claim(uuid, state.progress(), state.claimable(), today) ? state.claimable() : 0;
        }, claimed -> {
            if (claimed == 0) {
                messages.send(player, "daily.already", "time", timeUntilTomorrow(player));
                open(player);
                return;
            }
            give(player, rewards.day(claimed), rewards.day(claimed).pick());
            open(player);
        }, () -> messages.send(player, "error.generic"));
    }

    /** Thread principal. */
    private void give(Player player, Day day, Loot loot) {
        boolean dropped = false;
        for (Reward reward : loot.items()) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(ItemStack.of(reward.material(), reward.amount()));
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItem(player.getLocation(), item); // posé sur place, sans dispersion
                dropped = true;
            }
        }
        for (String command : loot.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        }
        if (loot.money() > 0) {
            Economy economy = Money.economy();
            UUID uuid = player.getUniqueId();
            Tasks.async(plugin, () -> economy.depositPlayer(Bukkit.getOfflinePlayer(uuid), loot.money()),
                    "Récompense du jour non versée à " + player.getName() + " (" + loot.money() + ")");
        }

        player.sendMessage(messages.prefix().append(messages.render(raw(player, "daily.claimed"),
                Placeholder.component("content", describe(player, loot)),
                "day", String.valueOf(day.number()))));
        if (dropped) {
            messages.send(player, "daily.dropped");
        }
        boolean last = day.number() == DailyState.CYCLE;
        player.playSound(player, last ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        player.getWorld().spawnParticle(last ? Particle.TOTEM_OF_UNDYING : Particle.HAPPY_VILLAGER,
                player.getLocation().add(0, 1, 0), last ? 60 : 25, 0.5, 0.8, 0.5, last ? 0.3 : 0);
    }

    /** Contenu d'un tirage : son label, sinon « 100 Heloks, 3× Diamant ». */
    Component describe(Player viewer, Loot loot) {
        if (loot.label() != null) {
            return messages.render(loot.label(), TagResolver.empty());
        }
        List<Component> parts = new ArrayList<>();
        if (loot.money() > 0) {
            Economy economy = Money.economy();
            parts.add(Component.text(economy != null ? economy.format(loot.money()) : String.valueOf(loot.money())));
        }
        for (Reward reward : loot.items()) {
            parts.add(Component.text(reward.amount() + "× ").append(Component.translatable(reward.material().translationKey())));
        }
        if (parts.isEmpty()) {
            parts.add(messages.get(viewer, "daily.loot.surprise"));
        }
        return Component.join(JoinConfiguration.commas(true), parts);
    }

    /** Temps avant minuit (nouveau jour), mis en forme. */
    String timeUntilTomorrow(Player viewer) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        long seconds = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).toSeconds();
        return EterLib.get().formatDuration(viewer, Math.max(0, seconds));
    }

    long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    DailyRewards rewards() {
        return rewards;
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    private String raw(Player player, String key) {
        String raw = messages.raw(player, key);
        return raw == null ? key : raw;
    }

}
