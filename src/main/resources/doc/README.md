# AuthMeBia — Documentation

This folder contains documentation for AuthMeBia's configurable subsystems.
All files mentioned below live inside `plugins/AuthMeBia/` unless stated otherwise.

---

## MiniMessage Formatting

All text fields in `config.yml` and all language files support full
[MiniMessage](https://docs.advntr.dev/minimessage/format.html) formatting:

| Tag example | Effect |
|---|---|
| `<red>`, `<gold>`, `<#4287f5>` | Colors |
| `<bold>`, `<italic>`, `<underlined>` | Decorations |
| `<gradient:red:blue>text</gradient>` | Gradient |
| `<rainbow>text</rainbow>` | Rainbow |
| `<font:minecraft:alt>text</font>` | Custom font |
| `<hover:show_text:'tip'>text</hover>` | Hover tooltip |
| `<click:open_url:'https://...'>text</click>` | Click action |
| `<key:jump>` | Keybind display |

Use `{player}` anywhere as a placeholder for the player's name.
Use `\n` inside any string to insert a line break.

---

## Language / Localization (`lang/`)

AuthMeBia externalizes all player-facing messages into YAML language files stored
in `plugins/AuthMeBia/lang/`.  Two files are included out of the box:

| File      | Language    |
|-----------|-------------|
| `lang/en.yml` | English |
| `lang/vi.yml` | Vietnamese |

### Selecting a language

Set the `lang` key in `config.yml`:

```yaml
lang: en   # or: vi
```

Reload with `/bia reload` — no server restart needed.

### Message categories

Each language file has three sections:

**`disconnect`** — shown on the disconnect / kick screen.

| Key | When shown |
|-----|------------|
| `verification_failed` | Captcha not solved |
| `registration_cancelled` | Player closed the register dialog |
| `must_agree_rules` | Player declined the rules checkbox |
| `login_failed` | Player exceeded wrong-password attempts (pre-spawn) |
| `logout` | Player clicked the Logout button |
| `too_many_attempts` | Per-session attempt limit hit (in-game dialog) |
| `ip_banned` | IP temporarily banned after crossing the `ip_ban.threshold` |

**`error`** — inline labels appended to the dialog input field on failure.

| Key | When shown |
|-----|------------|
| `wrong_password` | Password does not match |
| `password_empty` | Submitted an empty password |
| `passwords_mismatch` | Register confirm field does not match |
| `captcha_incorrect` | Wrong captcha code |

**`message`** — in-game chat messages sent to the player after they have spawned
(only used when `dialog.menu` is `false`, i.e. the in-game / post-spawn dialog path).

| Key | When shown |
|-----|------------|
| `wrong_password` | Wrong password in the in-game login dialog |
| `password_empty_or_mismatch` | Empty or mismatched password in the in-game register dialog |

### Placeholders

All message values support:

| Placeholder | Replaced with |
|-------------|--------------|
| `{player}` | The player's username |
| `{player_ip}` | The player's IP address |

### Adding a custom language

1. Copy `lang/en.yml` to `lang/<code>.yml` (e.g. `lang/fr.yml`).
2. Translate the values.
3. Set `lang: fr` in `config.yml`.
4. Run `/bia reload`.

---

## Custom Screens

AuthMeBia includes a mini-framework for defining fully custom dialog screens that
admins can show to players on demand or automatically on join.

### Defining a screen

Add entries to `custom_screens` in `config.yml`:

```yaml
custom_screens:
  - id: welcome
    enabled: true
    title: "<gradient:gold:yellow>Welcome!</gradient>"
    content: "<gray>Hello, <white>{player}</white>!\nEnjoy your stay.</gray>"
    allow_close: true
    button_width: 200
    trigger: postjoin
    sound_on_show: "minecraft:entity.player.levelup 0.5 1.2"
    buttons:
      - label: "<green>Play!</green>"
        action: close
        sound: "minecraft:ui.button.click"
      - label: "<aqua>Rules</aqua>"
        action: command
        value: "/rules"
      - label: "<#5865F2>Discord</#5865F2>"
        action: open_url
        value: "https://discord.gg/abc"
```

### Screen fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | string | required | Unique ID used with `/bia screen <id>` |
| `enabled` | boolean | `true` | Enable/disable without removing the entry |
| `title` | string | `"Notice"` | Dialog title. Full MiniMessage. |
| `content` | string | `""` | Dialog body. Full MiniMessage + `\n`. |
| `allow_close` | boolean | `true` | Whether ESC/click-outside closes the dialog |
| `button_width` | int | `dialog.button_width` | Default width for buttons in this screen |
| `trigger` | string | `command` | When the screen auto-shows (see below) |
| `sound_on_show` | string | `""` | Sound played when the screen opens (in-game only) |

### `trigger` values

| Value | Behaviour |
|-------|-----------|
| `command` | Only shown via `/bia screen <id> [player]` (default) |
| `postjoin` | Shown automatically after the player authenticates and spawns |
| `prejoin` | Shown during the pre-spawn phase (blocking), after auth, before spawn |

When `trigger: postjoin` or `trigger: prejoin`, the screen is shown on **every
login** while `enabled: true`. Set `enabled: false` when you no longer want
it auto-shown.

### Button fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `label` | string | `"OK"` | Button text. Full MiniMessage + `{player}`. |
| `action` | string | `close` | What happens when clicked (see below) |
| `value` | string | `""` | URL, text, or command. Supports `{player}`. |
| `width` | int | `button_width` | Button width in pixels |
| `sound` | string | `""` | Sound played on click (in-game only) |

### Button `action` values

| Value | Behaviour |
|-------|-----------|
| `close` | Closes the dialog |
| `open_url` | Opens `value` as a URL in the player's browser |
| `copy` | Copies `value` to the player's clipboard |
| `command` | Runs `value` as a command dispatched by the player |
| `console` | Runs `value` as a command dispatched by the console |

### Sound format

Both `sound_on_show` (per screen) and `sound` (per button) use the same format:

```
"namespace:sound.name"
"namespace:sound.name volume pitch"
```

Examples:
- `"minecraft:ui.button.click"`
- `"minecraft:entity.player.levelup 0.5 1.2"`
- `"minecraft:block.note_block.harp 1.0 2.0"`

Sounds only play in the in-game (post-spawn) path. They have no effect during
the pre-spawn blocking phase.

### Showing a screen via command

```
/bia screen <id>            Show screen to yourself (must be in-game)
/bia screen <id> <player>  Show screen to another online player
```

Tab completion is available for `<id>` (lists all configured screen IDs) and
`<player>` (lists online players). The same tab completion applies to
`/bia add <player>`, `/bia rm <player>`, and `/bia recover <player>`.

---

## Old Client Compatibility

The vanilla Dialog UI used for register/login menus only exists from
Minecraft 1.21.6 (protocol 771) onward. Clients on an older version
(connecting through ViaVersion or a fork) cannot render a dialog packet at
all -- if one is sent anyway, the connection just sits there until the
server's own network read-timeout eventually kicks the player, which looks
like the connection freezing before a disconnect.

AuthMeBia checks each connecting client's protocol version, on both the
pre-spawn (`dialog.menu: true`) and post-spawn (`dialog.menu: false`) paths,
before ever sending a dialog. Clients below `dialog.min_protocol_version`
skip every AuthMeBia dialog and authenticate with AuthMe's plain `/login`
and `/register` commands instead -- the freeze-then-kick never happens.
See the `dialog.min_protocol_version` comment in `config.yml` for details.

---

## Bypass List (`data/<uuid>/player.yml`)

Some players need to skip every AuthMeBia dialog entirely -- pre-spawn,
post-spawn, captcha, register, login, and rule -- and authenticate with
AuthMe's own plain `/login` and `/register` commands instead, as if
AuthMeBia were disabled for them specifically. This is managed through a
bypass list.

### Commands

| Command | Permission | Effect |
|---------|------------|--------|
| `/bia add <player>` or `/authmebia add <player>` | `authmebia.bypass` | Adds the player to the bypass list |
| `/bia rm <player>` or `/authmebia rm <player>` | `authmebia.bypass` | Removes the player from the bypass list |

The target player must either be online right now, or have joined this
server before (so the server already has their UUID cached). New players
who have never connected cannot be added by name in advance.

### Storage format

Each bypass entry is its own file at:

```
plugins/AuthMeBia/data/<uuid>/player.yml
```

```yaml
name: Notch
uuid: 069a79f4-44e9-4726-a5be-fca90e38aaf5
added: "2026-06-22T10:15:30Z"
```

| Field | Description |
|-------|--------------|
| `name` | The player's name at the time they were added |
| `uuid` | The player's UUID (also the folder name) |
| `added` | UTC timestamp (ISO-8601) of when the entry was created |

---

## Blindness Effect Interaction

AuthMe can apply a `Blindness` potion effect to players until they log in
(`settings.applyBlindEffect: true` in AuthMe's own `config.yml`). Because
AuthMeBia authenticates players through AuthMe's `forceRegister`/
`forceLogin` API methods rather than AuthMe's normal command flow, AuthMe's
own blindness removal can occasionally run on the wrong tick relative to
that force-login call and leave the effect stuck on an already-logged-in
player. AuthMeBia detects this case and explicitly removes the effect
immediately after every successful force-login/force-register, whenever
AuthMe's `applyBlindEffect` setting is on. No configuration is needed for
this; it is always active alongside that AuthMe setting.

---

## Welcome Image (`welcome.json`)

The welcome image is generated when a player registers and (optionally) posted
to Discord. It is built from a layered canvas defined in `welcome.json`.

Enable the feature in `config.yml`:

```yaml
welcome_image:
  enabled: true
```

If `discord.enabled` is `true` and a `discord.webhook_url` is set, the image is
also sent to that webhook.

### Top-level fields

| Field           | Type   | Required | Description |
|-----------------|--------|----------|-------------|
| `welcome_size`  | object | yes | Canvas size: `{ "width": ..., "height": ... }` |
| `welcome_style` | string | no  | Free-form label for your own reference (not read by the plugin). |
| `radial`        | number | no  | Corner radius in pixels. `0` or omitted = square corners. |
| `layers`        | array  | yes | List of layer objects drawn onto the canvas. |

### Layer objects

Every layer is a JSON object inside the `layers` array.

| Field   | Type   | Applies to        | Description |
|---------|--------|-------------------|-------------|
| `name`  | string | all               | Identifier. `background` and `player_name` are special (see below). |
| `z`     | number | all               | Stacking order — lower values are drawn first (further back). |
| `x`     | number | non-background    | X position in pixels (top-left corner). |
| `y`     | number | non-background    | Y position in pixels (top-left corner). |
| `size`  | number | image / text      | For images: width and height in pixels (square). For text: font size in pixels. |
| `color` | string | background / text | Hex color, e.g. `"#1a1a2e"`. |
| `patch` | string | image             | Path to a local file (relative to the plugin data folder), or `"{player_avatar}"`. |
| `url`   | string | image             | Remote image URL. Ignored if `patch` is set. |
| `font`  | string | text              | Font family name installed on the server's JVM/OS, e.g. `"Arial"`. |

#### `background` layer

A layer named `"background"` always fills the entire canvas, in this priority:

1. `color` filled first (flat background).
2. `patch` drawn on top if present (local image file).
3. If no `patch`, `url` is drawn instead.

#### `player_name` layer

A layer named `"player_name"` draws the joining player's username using its
`color`, `font`, and `size`, positioned at `x`/`y`.

Any other text layer (has `color` + `font` but a different name) draws its
`name` value as static text — useful for captions or titles.

#### Player avatar (`patch: "{player_avatar}"`)

```json
{
  "name": "avatar",
  "z": 1,
  "patch": "{player_avatar}",
  "x": 20,
  "y": 20,
  "size": 160
}
```

The plugin reads the player's skin from their live session, crops the 8×8 head
region, and scales it to `size`×`size` pixels. Works in both online-mode and
offline-mode. Falls back to Crafatar on rare texture-read failures.

### Full example

```json
{
  "welcome_size": { "width": 800, "height": 200 },
  "welcome_style": "horizontal",
  "radial": 16,
  "layers": [
    { "name": "background", "z": 0, "patch": "background.png", "color": "#1a1a2e" },
    { "name": "avatar", "z": 1, "patch": "{player_avatar}", "x": 20, "y": 20, "size": 160 },
    { "name": "player_name", "z": 2, "color": "#ffffff", "font": "Arial", "size": 48, "x": 200, "y": 80 },
    { "name": "Welcome to the server!", "z": 2, "color": "#aaaaaa", "font": "Arial", "size": 20, "x": 200, "y": 130 }
  ]
}
```
