package fr.eternom.eterReward.module.daily;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.api.DailyClaimEvent;
import fr.eternom.eterReward.module.daily.DailyRewards.Day;
import fr.eternom.eterReward.module.daily.DailyRewards.Loot;
import fr.eternom.eterReward.module.daily.DailyRewards.Reward;
import fr.eternom.eterReward.module.pass.PassService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
    private final PassService pass;
    /** Minutes de jeu actif dans la journée avant de pouvoir réclamer (un second compte connecté pour la récompense ne l'a pas). */
    private final int minActiveMinutes;

    public DailyService(JavaPlugin plugin, DailyStore store, DailyRewards rewards, Messages messages, ZoneId zone,
                        BackButton backButton, PassService pass, int minActiveMinutes) {
        this.minActiveMinutes = Math.max(0, minActiveMinutes);
        this.pass = pass;
        this.plugin = plugin;
        this.store = store;
        this.rewards = rewards;
        this.messages = messages;
        this.zone = zone;
        this.backButton = backButton;
    }

    /** /daily : ouvre le menu avec l'état du joueur. */
    public int streak(UUID player) {
        long today = today();
        DailyState state = DailyState.of(store.get(player), today);
        return state.streakLost(today) ? 0 : state.claimed();
    }

    public boolean claimedToday(UUID player) {
        return store.get(player).lastClaim() == today();
    }

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
        if (day.givesMoney() && EconomyApi.get().isEmpty()) {
            messages.send(player, "daily.economy-missing");
            return;
        }
        UUID uuid = player.getUniqueId();
        long today = today();
        Tasks.async(plugin, player, () -> {
            DailyState state = DailyState.of(store.get(uuid), today);
            if (state.canClaim() && minActiveMinutes > 0) {
                int played = pass.activeMinutesToday(uuid);
                if (played < minActiveMinutes) {
                    return new Claimed(0, DailyStore.Progress.NONE, minActiveMinutes - played);
                }
            }
            if (!state.canClaim() || !store.claim(uuid, state.progress(), state.claimable(), today)) {
                return Claimed.NONE;
            }
            return new Claimed(state.claimable(), state.progress(), 0);
        }, result -> {
            if (result.missing() > 0) {
                messages.send(player, "daily.play-first", "minutes", String.valueOf(minActiveMinutes),
                        "time", EterLib.get().formatDuration(player, result.missing() * 60L));
                Sounds.refuse(player);
                return;
            }
            int claimed = result.day();
            if (claimed == 0) {
                messages.send(player, "daily.already", "time", timeUntilTomorrow(player));
                open(player);
                return;
            }
            give(player, rewards.day(claimed), rewards.day(claimed).pick());
            // XP du pass seulement une fois la récompense donnée : une réclamation annulée (départ) ne la donne pas deux fois
            Tasks.async(plugin, () -> pass.dailyClaimed(uuid), "XP du pass non donnée pour /daily à " + player.getName());
            Bukkit.getPluginManager().callEvent(new DailyClaimEvent(player, claimed));
            open(player);
        }, () -> messages.send(player, "error.generic"), result -> {
            if (result.day() > 0) {
                // Parti avant de recevoir quoi que ce soit : la réclamation est annulée, il la refera
                Tasks.async(plugin, () -> store.undo(uuid, result.read(), result.day(), today),
                        "Récompense du jour non annulée pour " + uuid);
            }
        });
    }

    /** Le jour réclamé (0 : rien), la progression lue avant (pour annuler si le joueur part), les minutes de jeu manquantes. */
    private record Claimed(int day, DailyStore.Progress read, int missing) {

        static final Claimed NONE = new Claimed(0, DailyStore.Progress.NONE, 0);
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
            EconomyApi economy = EconomyApi.get().orElse(null);
            UUID uuid = player.getUniqueId();
            Tasks.async(plugin, () -> economy.deposit(uuid, loot.money(), "EterReward · récompense du jour"),
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
            parts.add(Component.text(Money.format(loot.money())));
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
