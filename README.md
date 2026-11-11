# TreeFarm (SaplingFarm)

A Paper/Spigot plugin that lays out a grid of trees, previews it in the world with holograms and
particles, builds it only once you confirm (consuming saplings, torches and optional dirt from your
inventory), and removes it again with a leaf-aware felling pass.

## Commands

```
/tf <type> <W> <L> [spacing] [lit]
/tf confirm      # build the previewed farm, or confirm a pending fell
/tf cancel       # discard the preview / pending fell
/tf relocate     # move the preview's nearest corner to your feet
/tf fell         # stand inside a farm, then /tf confirm to remove it
/tf help
```

Aliases: `treefarm`, `tf`, `saplingfarm`, `sfarm`, `farm`, `farmgrid`.

- `W` / `L` are the number of **trees** (2..20), not sapling columns.
- `spacing` is the gap between trees. Singles default to 4 (min 3); 2x2 trees default to 7 (min 6).
  Maximum spacing is 15.
- `lit` lays an even torch grid through the gaps, skipping any cell inside a trunk.

### Types

`oak`, `spruce`, `birch`, `jungle`, `acacia`, `cherry`, `mangrove`, `pale_oak`,
`jungle_large`, `spruce_large`, `dark_oak` (2x2 only), and `random`.

`random` plants whatever saplings you are carrying (dark oak excluded) and has no large option.

### Felling

Stand inside a farm and run `/tf fell`, then `/tf confirm` within the timeout. The farm's allocated
plot box is recorded when it is built, and felling clears tree wood inside that box by anchoring on
leaves: it seeds a flood fill at every log touching a leaf, then removes the connected logs, so trunks
come down together with their branches. A log that is not connected to any canopy is left alone, which
preserves a player build placed inside the plot. Torches are returned to your inventory. Leaves are
left to decay naturally.

## Permissions

| Node | Default | Meaning |
| --- | --- | --- |
| `treefarm.use` | true | Use tree farm commands |
| `treefarm.admin` | op | Remove any farm, ignore limits |
| `treefarm.farmlimit.<n>` | false | Raise a player's placed-farm cap to `n` |
| `treefarm.farmlimit.unlimited` | false | No cap |

## Configuration

See `src/config.yml`: spacing defaults/minimums, the `lit` torch spacing, size bounds (2..20), the
per-player farm limit, the fell range/height, the preview timeout, and the block-display / particle
toggles. Values are read live, so a restart or `/reload confirm` applies edits.

## Building

The plugin compiles against the Paper API and (optionally) GriefPrevention. Drop the jars in `lib/`:

```
lib/paper-api.jar
lib/GriefPrevention.jar    # only needed if you want GriefPrevention claim checks
```

Then:

```
./build.sh
```

This compiles `src/` to `out/` and packages `TreeFarm.jar`. Copy that jar into your server's
`plugins/` folder. GriefPrevention is a soft dependency: without it the plugin still loads and simply
skips claim checks.

## Notes

- Farms are tracked in `plugins/SaplingFarm/farms.yml`.
- A preview never overlaps an existing farm, even in your own claim or as an admin.
- Placing or felling requires the ability to build in every affected spot (claims are respected).
