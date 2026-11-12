# TreeFarm (SaplingFarm)

A Paper/Spigot plugin that lays out a grid of trees, previews it in the world with holograms and
particles, builds it only once you confirm (consuming saplings, torches and optional dirt from your
inventory), and removes it again on command.

Current version: **2.2.0**. See [VERSION-REPORT.txt](VERSION-REPORT.txt) for the tested feature
matrix and known limitations, and [SECURITY.md](SECURITY.md) for the security and privacy notes.

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

### How felling works

When a farm is built the plugin records the plot bounds, and as the saplings grow it records the
actual log and leaf blocks they turn into. To remove a farm you stand inside it and run `/tf fell`,
then `/tf confirm`. The plugin clears exactly the blocks it recorded for that farm, so a player build
placed inside the plot is left untouched, and the farm's torches are returned to your inventory.
Removal runs as a chunk-batched job, so large farms clear over successive ticks instead of in one
spike.

## Permissions

| Node | Default | Meaning |
| --- | --- | --- |
| `treefarm.use` | true | Use tree farm commands |
| `treefarm.admin` | op | Remove any farm, ignore limits |
| `treefarm.farmlimit.<n>` | false | Raise a player's placed-farm cap to `n` |
| `treefarm.farmlimit.unlimited` | false | No cap |

## Configuration

See `src/config.yml`: spacing defaults/minimums, the `lit` torch spacing, size bounds (2..20), the
per-player farm limit, the preview timeout, and the preview marker settings. Values are read live,
so a restart (or `/reload confirm`) applies edits.

## Building

`build.sh` compiles against the libraries of a booted Paper server plus the GriefPrevention
compile-time jar:

```
./build.sh /path/to/paper-server /path/to/GriefPrevention.jar
```

Output: `build/SaplingFarm-2.2.0.jar`. Copy it into your server's `plugins/` folder.

GriefPrevention is a soft dependency: without it the plugin still loads and simply skips claim checks.

## Notes

- Farm records live in `plugins/SaplingFarm/farms.db` (SQLite). An existing `farms.yml` from an
  older version is imported on first start and left in place.
- A preview never overlaps an existing farm, even in your own claim or as an admin.
- Placing or felling requires the ability to build in every affected spot (claims are respected).
