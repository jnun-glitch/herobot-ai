# HeroBot — Wiki 1:1 (wörtliche Abschrift)

> Quelle: https://github.com/HerobaneNair/HeroBot (README, Branch `master`, abgerufen 2026-10-30)
> Zweck: einfache Analyse, welche Aktionen HeroBot kann und welche daraus für unseren Kampfcontroller folgen.
> Dies ist eine unveränderte Abschrift des README-Inhalts (übersetzt bleibt Englisch, damit die Befehle exakt stimmen).

---

This mod is made for datapack makers to make bots that can fight back

The bots have a much higher parity with a vanilla client compared to carpet, with things like Auto-Jump and the spear working.

---
## Incompatible With Carpet Mod and Carpet PVP

---

## Settings

Views and modifies HeroBot settings via the `/herobot` command. Running `/herobot` with no arguments displays the mod version.

**Usage**
- `/herobot <setting>` - Displays the current value and description
- `/herobot <setting> <value>` - Sets the setting temporarily for this session
- `/herobot <setting> <value> <perm>` - Sets the setting permanently (saved to config)

### Bots

- `allowSpawningOfflinePlayers` — Spawn offline players in online mode if an online-mode player with the specified name does not exist. Default: true
- `allowListingBotPlayers` — Allows bot players to appear in the multiplayer player list. Default: true
- `botPingToTicks` — Changes the ping to tick conversion rate for bot players. Used when converting a bot's ping (in ms) to a tick delay for knockback. Default: 25
- `botLagAttacks` — [Experimental] Makes left clicks (attack) on bot players delayed by the bot's ping value. Default: false
- `botLagUses` — [Experimental] Makes right clicks (use) on bot players delayed by the bot's ping value. Default: false
- `botLeaveOnDeath` — Bot players disconnect from the server on death instead of automatically respawning. Default: false

### Creative

- `creativeNoClip` — Creative no clip mode. Allows creative players who are flying to phase through blocks. Default: false
- `creativeFlySpeed` — Changes the creative flying speed multiplier. Higher values make the client fly faster. Default: 1.0
- `creativeFlyDrag` — Changes creative air drag. Lower values reduce air resistance while flying. Default: 0.09

### Combat

- `shieldStunning` — Enables shield stunning, allowing entities to be damaged immediately after a shield is disabled. Default: false
- `noProjectileRandom` — Removes randomness from projectiles, making them perfectly accurate. Default: false

### Explosions

- `explosionNoBlockDamage` — Controls whether explosions destroy blocks.
  - `FALSE` - vanilla behavior
  - `MOST` - affects non-solid blocks except glowstone and redstone-interaction blocks
  - `TRUE` - prevents all block damage
  - Default: FALSE
- `explosionNoFire` — Prevents explosions from beds and respawn anchors from creating fire. Default: false
- `windChargeNoTrigger` — Wind charges won't activate redstone blocks. Default: false

### World / Misc

- `clientsIgnoreSlowTickRate` — If false, makes client players ignore slower tick rates. Default: true
- `editablePlayerNbt` — Allows editing player NBT data directly. Also allows mounting players via `/ride` when enabled. Default: false
- `xpNoCooldown` — Players absorb experience instantly without delay. Default: false
- `shulkerBoxAlwaysDrops` — Shulker boxes will always drop, regardless of the `doTileDrops` gamerule. Default: false
- `deleteChunkEntities` — When enabled, chunk reset/deletion also deletes entity data files within those chunks. Default: false
- `rainThroughMovingPiston` — Allows rain to fall through blocks being moved by pistons. Default: false
- `disableExperimentalScreen` — Remove the experimental world setting. If set on the client, disables all experimental screens. If set on a world, only disables it for that world. Default: false

---

## Bot Player

Bot players are fake players that join the server and can be controlled entirely through commands. They are spawned with `/playerspawn` and controlled with `/player`. Bot players have correct server-side behavior for combat, movement, and item usage.

Bot players automatically respawn on death with full health, empty inventory, and no XP (unless `botLeaveOnDeath` is enabled).

### `/playerspawn`

Spawns a bot player with optional position, rotation, gamemode, and dimension.

- `/playerspawn <name>` - spawns at the executor's position
- `/playerspawn <name> at <pos>` - spawns at a specific position
- `/playerspawn <name> at <pos> facing <rotation>` - spawns with specific yaw/pitch
- `/playerspawn <name> at <pos> facing <cardinal>` - spawns facing a cardinal direction (`north`, `south`, `east`, `west`, `up`, `down`)
- `/playerspawn <name> ... in <gamemode>` - spawns in a specific gamemode (defaults to the sender's own gamemode, or survival if the sender is a spectator or not a player)
- `/playerspawn <name> ... on <dimension>` - spawns in a specific dimension

Prevents duplicate names. Respects whitelist and bans. Spectators spawn flying, survival players spawn grounded.

### `/player`

Controls bot players using action packs. All subcommands target with `/player <targets> ...` and support selectors for targeting multiple bots.

#### Actions: `use`, `attack`, `swing`, `jump`, `drop`, `dropStack`, `swapHands`

Action commands that make the bot perform player actions.

- `/player <targets> <action>` - stops that action
- `/player <targets> <action> once` - performs the action once
- `/player <targets> <action> continuous` - performs the action every tick
- `/player <targets> <action> interval <ticks>` - performs the action every N ticks
- `/player <targets> <action> twice` - performs the action twice on the same tick (`attack` and `use` only)

`use` without parameters stops using without triggering another use (unlike carpet where it behaved like `use once`).

`use twice` runs the hand scan twice in one tick, so once the main hand item goes on cooldown the second pass falls through to the offhand - e.g. a wind charge in the main hand and an ender pearl in the offhand both fire on the same tick. `attack twice` is meant for shield stunning.

`swing` swings a hand without performing an action and resets the attack cooldown.

`attack` with a spear/kinetic weapon will perform a stab action instead.

#### `autojump`

Controls automatic jumping over one-block obstacles while moving

- `/player <targets> autojump` - attempts a single autojump (jumps if the bot is moving and would need to step up)
- `/player <targets> autojump true` - enables continuous autojump
- `/player <targets> autojump false` - disables continuous autojump

#### `move`

Controls bot movement direction.

- `/player <targets> move` - stops all movement
- `/player <targets> move forward`
- `/player <targets> move backward`
- `/player <targets> move left`
- `/player <targets> move right`

#### `sneak` / `sprint`

- `/player <targets> sneak` - starts sneaking
- `/player <targets> unsneak` - stops sneaking
- `/player <targets> sprint` - starts sprinting
- `/player <targets> unsprint` - stops sprinting

Sneaking slows movement to 30% speed. Using an item (non-spear) slows to 20% speed.

#### `look`

Controls where the bot is looking. Most look commands support `delta <ticks>` for smooth transitions over multiple ticks.

**Cardinal Directions**
- `/player <targets> look north|south|east|west|up|down`
- `/player <targets> look north delta <ticks>`

**Exact Rotation**
- `/player <targets> look <yaw> <pitch>`
- `/player <targets> look <yaw> <pitch> delta <ticks>`

**Relative Turns**
- `/player <targets> look left` - turns 90 degrees left
- `/player <targets> look right` - turns 90 degrees right
- `/player <targets> look back` - turns 180 degrees
- `/player <targets> look relative <rotation>` - turns by the given relative rotation
- All support `delta <ticks>`

**Look at Position**
- `/player <targets> look at <pos>`
- `/player <targets> look at <pos> delta <ticks>`

**Look at Entity**
- `/player <targets> look upon <entity>` - looks at the entity's eyes (default)
- `/player <targets> look upon <entity> eyes` - looks at the entity's eyes
- `/player <targets> look upon <entity> feet` - looks at the entity's feet
- `/player <targets> look upon <entity> closest` - looks at the closest point of the entity's hitbox
- All support `delta <ticks>`

**Random**
- `/player <targets> look random` - looks in a random direction

#### `hotbar`

Selects a hotbar slot.

- `/player <targets> hotbar <slot>` - selects slot 1 through 9

#### `itemCd`

Controls item cooldowns.

- `/player <targets> itemCd` - resets all item cooldowns (returns how many were reset)
- `/player <targets> itemCd <item>` - shows remaining cooldown ticks (returns tick count)
- `/player <targets> itemCd <item> reset` - resets the cooldown for that item
- `/player <targets> itemCd <item> set` - applies the item's default cooldown without using it
- `/player <targets> itemCd <item> set <ticks>` - applies a custom cooldown duration

#### `ping`

Controls the bot's simulated ping. Knockback is delayed by the equivalent number of ticks based on the `botPingToTicks` setting.

- `/player <targets> ping` - shows the bot's current ping and equivalent tick delay
- `/player <targets> ping <value>` - sets the ping in milliseconds (suggested: 0, 25, 50, 100, 150, 200)

The tick delay has some randomness when the ping doesn't divide evenly by `botPingToTicks`. For example, with ping 30 and botPingToTicks 25, there is a 5/25 chance each tick to add 1 extra tick of delay.

#### `copycat`

Copies another player's inventory and action pack onto the bot.

- `/player <targets> copycat <source>`

#### `skin` / `handedness`

Toggles visibility of individual skin parts on the bot.

Skin parts:
- `/player <targets> skin cape`
- `/player <targets> skin jacket`
- `/player <targets> skin leftSleeve`
- `/player <targets> skin rightSleeve`
- `/player <targets> skin leftPant`
- `/player <targets> skin rightPant`
- `/player <targets> skin hat`

Each call toggles the part on/off.

Left/Right Handedness:
- `/player <targets> handedness left`
- `/player <targets> handedness right`

#### `stop` / `kill` / `disconnect`

- `/player <targets> stop` - stops all actions and movement
- `/player <targets> kill` - kills the bot (triggers death and respawn)
- `/player <targets> disconnect` - removes the bot from the server without a death

---

## Distance

Measures distance between two positions, entities, or hitboxes and stores the result as the command return value (scoreboard-friendly). Default distance is spherical (3D).

### `from`

Point-to-point distance using exact positions.

- `/distance from <pos> to <pos>`
- `/distance from <pos> to <entity>`
- `/distance from <entity> to <pos>`
- `/distance from <entity> to <entity>`

### `fromHitbox`

Uses the full bounding box of the source instead of its center point.

- `/distance fromHitbox <block> to <pos|entity>`
- `/distance fromHitbox <entity> to <pos|entity>`

Can also be combined with `toHitbox`:
- `/distance from <pos|entity> toHitbox <block>`
- `/distance from <pos|entity> toHitbox <entity>`
- `/distance fromHitbox <block|entity> toHitbox <block|entity>`

### `e` (Exponent Scaling)

- `/distance ... e <exp>`
- Multiplies the distance by 10^exp before returning
- Example: 4 blocks with `e 3` returns `4000`

### `horizontal`

Measures XZ distance only (ignores Y).

- `/distance ... horizontal`
- Supports `e <exp>`

### `vertical`

Measures Y distance only (ignores XZ).

- `/distance ... vertical`
- Supports `e <exp>`

---

## Delayed

Schedules commands or functions to run after a tick delay.

### `tickDelay`

Schedules a command or function to execute after a specified number of ticks.

- `/delayed tickDelay <ticks> command <command>` - runs a command after the delay, supports full auto-completion
- `/delayed tickDelay <ticks> function <function>` - runs a datapack function after the delay

### `queue`

View and manage pending delayed commands.

- `/delayed queue` - lists all pending delayed commands
- `/delayed queue entity <entity>` - lists delayed commands for a specific entity
- `/delayed queue entity` - lists delayed commands for the executing entity
- `/delayed queue remove <index>` - removes a specific delayed command by index

### `clear`

- `/delayed clear` - clears all pending delayed commands

---

## Chunk Resetter

Resets (deletes) chunk data so they regenerate fresh on next load. Loaded chunks are skipped and must be unloaded first.

### `chunk`

Resets specific chunks by block column position.

- `/chunk-resetter chunk <pos>` - resets the single chunk at the given position
- `/chunk-resetter chunk <from> <to>` - resets all chunks in the rectangular range (max 256 chunks)

Entities in reset chunks are discarded.

### `world`

Resets all unloaded chunks in an entire dimension. Also deletes POI data. Entity data files are only deleted if `deleteChunkEntities` is enabled.

- `/chunk-resetter world` - resets chunks in the current dimension
- `/chunk-resetter world <dimension>` - resets chunks in the specified dimension

---
