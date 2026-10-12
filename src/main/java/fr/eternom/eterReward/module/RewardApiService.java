package fr.eternom.eterReward.module;

import fr.eternom.eterReward.api.RewardApi;
import fr.eternom.eterReward.module.daily.DailyService;
import fr.eternom.eterReward.module.pass.PassService;
import org.bukkit.entity.Player;

import java.util.UUID;

/** L'API d'EterReward (RewardApi.get()) : la récompense du jour et le pass. */
public class RewardApiService implements RewardApi {

    private final DailyService daily;
    private final PassService pass;

    public RewardApiService(DailyService daily, PassService pass) {
        this.daily = daily;
        this.pass = pass;
    }

    @Override
    public int streak(UUID player) {
        return daily.streak(player);
    }

    @Override
    public boolean claimedToday(UUID player) {
        return daily.claimedToday(player);
    }

    @Override
    public void openMenu(Player player) {
        daily.open(player);
    }

    @Override
    public void addPassXp(UUID player, int amount) {
        pass.addXp(player, amount);
    }

    @Override
    public void openPass(Player player) {
        pass.open(player);
    }
}
