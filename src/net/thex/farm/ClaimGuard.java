package net.thex.farm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Abstraction over a land-claim plugin (GriefPrevention). A no-op guard is used when none is
 * installed.
 */
public interface ClaimGuard {

    /**
     * True if the player could build at this location (wilderness or a claim they may build in).
     */
    boolean canBuild(Player player, Location loc, Material mat);
}
