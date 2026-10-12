package fr.eternom.eterReward.module.challenge;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterReward.module.challenge.ChallengeDefinition.Kind;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Ce qui fait avancer les défis. Seulement en survie : un bloc posé par un joueur ne compte pas (EterLib PlacedBlocks),
 * une culture doit être mûre, une créature doit être tuée par le joueur.
 */
public class ChallengeListener implements Listener {

    private final ChallengeService challenges;

    public ChallengeListener(ChallengeService challenges) {
        this.challenges = challenges;
    }

    /** HIGH : après les protections (claims), avant que la note « posé » soit retirée (MONITOR). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        Material type = event.getBlock().getType();
        if (event.getBlock().getBlockData() instanceof Ageable crop && (Tag.CROPS.isTagged(type) || type == Material.NETHER_WART)) {
            if (crop.getAge() >= crop.getMaximumAge()) {
                challenges.count(player, Kind.HARVEST, challenge -> challenge.matchesBlock(type));
            }
            return;
        }
        if (EterLib.get().getPlacedBlocks().isPlaced(event.getBlock())) {
            return;
        }
        challenges.count(player, Kind.BREAK, challenge -> challenge.matchesBlock(type));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.getGameMode() != GameMode.SURVIVAL || event.getEntity() instanceof Player) {
            return;
        }
        boolean monster = event.getEntity() instanceof Enemy;
        challenges.count(killer, Kind.KILL, challenge -> challenge.matchesEntity(event.getEntityType(), monster));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            challenges.count(event.getPlayer(), Kind.FISH, challenge -> true);
        }
    }

    /** Appelé par l'écouteur des quêtes d'EterMarket (classe à part). */
    public void onQuest(Player player) {
        challenges.count(player, Kind.QUEST, challenge -> true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        challenges.quit(event.getPlayer().getUniqueId());
    }
}
