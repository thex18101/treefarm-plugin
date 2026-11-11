package net.thex.farm;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * GriefPrevention bridge. This class is only ever LOADED when GriefPrevention is present (it is
 * referenced from the no-op factory only after the plugin check), so the hard references below are
 * safe even though GriefPrevention is a soft dependency.
 */
public class GpGuard implements ClaimGuard {

    @Override
    public boolean claimsActive(World world) {
        try {
            return GriefPrevention.instance != null && GriefPrevention.instance.claimsEnabledForWorld(world);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean canBuild(Player player, Location loc, Material mat) {
        try {
            if (GriefPrevention.instance == null || loc.getWorld() == null) return true;
            if (!GriefPrevention.instance.claimsEnabledForWorld(loc.getWorld())) return true;
            Claim claim = GriefPrevention.instance.dataStore.getClaimAt(loc, false, null);
            if (claim == null) return true;                     // wilderness
            return claim.allowBuild(player, mat) == null;        // null reason == allowed
        } catch (Throwable t) {
            return true;                                         // never block on an API hiccup
        }
    }
}
