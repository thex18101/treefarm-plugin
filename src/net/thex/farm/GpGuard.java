package net.thex.farm;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Linked only when GP is installed. A loaded but unavailable integration denies world edits. */
public class GpGuard implements ClaimGuard {
    private boolean warned;

    @Override
    public boolean canBuild(Player player, Location loc, Material material) {
        try {
            GriefPrevention gp = GriefPrevention.instance;
            if (gp == null || !gp.isEnabled() || loc.getWorld() == null) return false;
            if (!gp.claimsEnabledForWorld(loc.getWorld())) return true;
            Claim claim = gp.dataStore.getClaimAt(loc, false, null);
            return claim == null || claim.allowBuild(player, material) == null;
        } catch (RuntimeException | LinkageError e) {
            if (!warned) {
                org.bukkit.Bukkit.getLogger()
                        .warning("[SaplingFarm] GriefPrevention lookup failed: " + e);
                warned = true;
            }
            return false;
        }
    }
}
