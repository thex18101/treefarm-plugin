Security & Privacy
==================

SaplingFarm is a self-contained Paper/Spigot plugin. This file records what it
does and does not do with respect to credentials, data and the network, and how
access is controlled.


CREDENTIALS
-----------
- No credentials are stored in this repository: no passwords, tokens, API keys,
  IP addresses, hostnames or usernames appear in the source, build script,
  manifest or config.
- The build reads nothing secret from the tree. At runtime the plugin reads only
  its own config.yml and its own data file.
- Do not put secrets in config.yml. The security-relevant settings there are
  ordinary tunables (sizes, spacing, limits, timeouts).


RUNTIME DATA (PRIVACY)
----------------------
- The plugin keeps its state locally in plugins/SaplingFarm/farms.db (SQLite).
  It stores: farm owner (player UUID and name), world name, plot bounds, and the
  block positions the plugin placed for each farm (saplings, torches, and the
  logs/leaves they grew into).
- That data never leaves the server. There is no upload, no sync, no backup to a
  third party.
- Removing a farm deletes its rows from the database.
- Put farms.db on your normal world backup rotation.


NETWORK
-------
- The plugin makes no outbound network requests. There is no telemetry,
  analytics, update check, or "phone home" behavior.
- Verified by inspection: the source contains no java.net / socket / HTTP client
  use, no process execution, and no environment-variable reads. It uses only the
  Bukkit/Paper API.


ACCESS CONTROL
--------------
- treefarm.use             use the commands (default: everyone)
- treefarm.admin           remove any farm and ignore per-player limits
                           (default: op)
- treefarm.farmlimit.<n>   raise a player's placed-farm cap to n
- treefarm.farmlimit.unlimited   no cap
- Where a claims plugin (GriefPrevention) is installed, placing and felling also
  require the player to be allowed to build in every affected position, and a
  farm cannot be placed overlapping an existing one, regardless of claims.
- Ownership is enforced: by default a player can only remove their own farms
  (an admin may remove any).


REPORTING
---------
Report a vulnerability or a concern to the repository owner rather than opening
a public issue if it is sensitive.
