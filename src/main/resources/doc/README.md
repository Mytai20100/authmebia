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

### PlaceholderAPI

If PlaceholderAPI is installed, every text field that goes through the
plugin's normal text-rendering path (dialog titles, content, button labels,
error messages, chat messages) also resolves `%placeholder%` expansions from
any installed PlaceholderAPI extension (e.g. LuckPerms prefixes, Vault
ranks), on top of `{player}` substitution and MiniMessage formatting.

This only applies while text is being rendered for a specific player (the
call is scoped to that render, not global), and is a soft dependency: if
PlaceholderAPI is not installed, or resolution fails for any reason, the
text is used unchanged with no error.

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

## Self-Service Password Recovery ("Forgot Password?")

In addition to admin-forced recovery (`/bia recover`), players can reset their
own password from the login dialog itself, without any admin involvement.

### Requirements

- `dialog.login.forgot_password.enabled: true` in `config.yml`.
- The account must already have an email on file. This means `email.enabled`
  must have been `true` at some point so the player verified an email during
  registration (see the `email` section of `config.yml`). Accounts registered
  before email verification was enabled, or that skipped it, have no email on
  file.
- AuthMe's own SMTP settings (`Email.mailAccount`, `Email.mailPassword`,
  `Email.mailSMTP`) must be configured, since the reset code is sent through
  the same `EmailService` used by registration email verification.

### Flow

1. On the login dialog, the player clicks **Forgot Password?** (label set by
   `dialog.login.forgot_password.button`).
2. If the account has no email on file, the player sees a message telling
   them to contact an admin (`no_email_message`) and the flow stops there —
   nothing is sent.
3. Otherwise, the player is asked to enter the email address registered to
   the account (`email_title` / `email_content` / `email_label`). This is a
   confirmation step, not a lookup — the address must match what AuthMe has
   stored, or the player sees `invalid_email_error`.
4. Once confirmed, a numeric code (length `email.code_length`) is emailed to
   that address, and the player sees the same verify dialog used for
   registration email verification, including the resend cooldown
   (`email.resend_cooldown`).
5. After the code is verified, the player is shown the same two-field reset
   dialog used by admin-forced recovery (`recover.*` in `config.yml`) to set
   a new password.

### Relevant config keys

All keys below live under `dialog.login.forgot_password` in `config.yml`
unless noted otherwise:

| Key | Description |
|-----|-------------|
| `enabled` | Master switch for the button and the whole flow |
| `button` / `button_sound` | Label and click sound for the Forgot Password button |
| `email_title` | Title reused for both the email-entry step and the "no email on file" notice |
| `email_content` | Body text of the email-entry step |
| `email_label` | Label of the email input field |
| `submit_button` / `submit_sound` | Label and click sound for the button that submits the entered email |
| `invalid_email_error` | Inline error when the entered email doesn't match the one on file |
| `no_email_message` | Shown instead of the email field when the account has no email on file |

The code-length, resend cooldown, and wrong-code error reuse the existing
`email.code_length`, `email.resend_cooldown`, and `email.wrong_code_error`
settings, since this step is the same verify dialog used during registration.

---

## Button Layout

`dialog.button_layout` controls how the main action buttons are arranged
inside the register and login dialogs:

| Value | Behaviour |
|-------|-----------|
| `vertical` (default) | Buttons are stacked one per row |
| `horizontal` | Buttons are placed side by side in a single row |

This applies consistently to every main button in both dialogs — Login,
Forgot Password, and Logout in the login dialog; Register and Logout in the
register dialog — plus any link buttons shown alongside them when
`links.position` is not `separated`. Custom screens and other standalone
dialogs (captcha, 2FA, recover, wait, rule) are unaffected and keep their own
layout.

---

## Button Click Sounds

Nearly every clickable button across AuthMeBia's dialogs supports an optional
click sound, configured with a `*_sound` key next to that button's label.
Examples: `dialog.register.submit_sound`, `dialog.login.submit_sound`,
`dialog.logout_sound`, `rule.agree_sound`, `captcha.submit_sound`,
`email.verify_sound`, `email.resend_sound`, `totp_2fa.submit_sound`,
`recover.submit_sound`, `auth_mode.pin.button_sound`,
`auth_mode.slider.button_sound`, and `dialog.login.forgot_password.button_sound`
/ `submit_sound`. Custom screens configure sounds per-screen and per-button
instead (`sound_on_show` and `sound`, see the Custom Screens section below).

Leave a `*_sound` key as `""` (the default) to play no sound.

### Sound format

```
"namespace:sound.name"
"namespace:sound.name volume pitch"
```

Examples:
- `"minecraft:ui.button.click"`
- `"minecraft:ui.button.click 1.0 1.0"`

Sounds only play in the in-game (post-spawn) path — the same restriction as
custom screen sounds. They have no effect during the pre-spawn blocking
phase, since that phase runs before the player has a client-side audio
context tied to a spawned entity.

---

## Brute-Force Protection

Two independent settings protect against repeated wrong-password attempts.
`login_attempts` is enabled by default; `ip_ban` is opt-in (disabled by
default).

### Login attempt limit (`login_attempts`)

Kicks the player after too many wrong passwords within a single login dialog
session (the counter resets on each new connection). Enabled by default.

| Key | Description |
|-----|-------------|
| `enabled` | Master switch. Default: `true` |
| `max_tries` | Number of wrong attempts allowed before a kick (minimum 1) |

The kick uses the `disconnect.too_many_attempts` message from the active
language file.

### IP ban (`ip_ban`)

Tracks wrong-password attempts per IP address across sessions (not just one
login dialog), and issues a temporary Bukkit IP ban once the threshold is
crossed. Repeated offenses from the same IP escalate to longer ban durations.

| Key | Description |
|-----|-------------|
| `enabled` | Master switch |
| `threshold` | Wrong attempts from the same IP before the first ban (minimum 1) |
| `ban_durations_seconds` | List of ban lengths in seconds, one per escalation level; the last value repeats for any further offense |

The default escalation is `[600, 1800, 3600, 86400]` — 10 minutes, 30
minutes, 1 hour, then 1 day for every offense after that. The ban reason
shown to the player uses the `disconnect.ip_banned` message, with
`{player_ip}` filled in.

Both counters are tracked in memory and reset when the plugin restarts.

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

## Toast Notifications

AuthMeBia can show the small vanilla advancement-style toast popup in the
corner of the screen on certain player milestones. Each toast is defined in
`notifications.toasts` in `config.yml`.

### Defining a toast

```yaml
notifications:
  toasts:
    - name: welcome_toast
      check: first_register
      title: "<gold>Welcome!</gold>"
      content: "<gray>Thanks for joining the server.</gray>"
      icon: "minecraft:player_head"
      sound: "minecraft:entity.player.levelup 0.6 1.0"
      delay: 5
      frame: task
```

### Toast fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | string | required | Identifier, used for logging and with `/bia notifier` |
| `check` | string | required | Which event triggers this toast (see below) |
| `title` | string | `""` | Larger, bottom line of the toast. Full MiniMessage. |
| `content` | string | `""` | Toast description text. Full MiniMessage. |
| `icon` | string | required | Minecraft item id shown on the toast, e.g. `minecraft:diamond` |
| `sound` | string | `""` | Sound played when the toast appears (see sound format above) |
| `delay` | int | required | How long the toast stays visible, in seconds (approximate) |
| `frame` | string | `task` | `task`, `goal`, or `challenge` — see the client limitation note below |

### `check` values

| Value | Fires on |
|-------|----------|
| `first_register` | The player's very first successful registration |
| `first_login` | The player's very first successful login |
| `first_message` | The player's first chat message this session |
| `first_advancement` | The player's first advancement/achievement this session |

Each toast only ever fires once per player per `check`.

### Client-side limitation on `frame`

Every vanilla advancement toast has two lines. The small line above is drawn
entirely by the client from `frame` and is not otherwise configurable — it
always reads "Advancement Made!" (`task`), "Goal Reached!" (`goal`), or
"Challenge Complete!" (`challenge`), translated into the viewing player's own
client language. The larger line is what `title` controls. There is no
vanilla mechanism to remove the small line entirely.

Changing `frame` on a toast that already registered earlier in the current
server run requires a full restart to take effect (this also applies to
changing `title`/`icon`/`content` on an already-registered toast — an
existing Bukkit advancement-registration limitation, not specific to this
plugin).

### Previewing a toast (`/bia notifier`)

```
/bia notifier <toast_name> <player> show [seconds]
```

Shows the named toast to an online player immediately, for testing. This
never touches the toast's persisted "already shown" state, so previewing a
toast never affects whether it still fires for real later. `[seconds]`
optionally overrides the configured `delay` for this preview only. Requires
OP. Tab completion lists configured toast names and online players.

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

## Platform Support (Paper / Folia)

AuthMeBia detects at startup whether it is running on Folia (checking for
`io.papermc.paper.threadedregions.RegionizedServer`) and switches every
scheduled task — dialog timeouts, wait delays, IP bans, welcome image
delivery — to Folia's region-aware `AsyncScheduler` / per-entity scheduler
instead of the standard Bukkit scheduler. No configuration is needed; this is
automatic. `/bia info` reports the detected platform (Paper, Folia, or plain
Bukkit).

### AuthMe built-in dialog conflict check

AuthMe itself has its own optional native dialog feature
(`settings.registration.dialog.preJoin.enable` /
`settings.registration.dialog.postJoin.enable` in AuthMe's `config.yml`). If
that is left enabled alongside AuthMeBia's dialogs, players would see two
separate dialog systems fighting over the same login/register flow. On
startup, AuthMeBia reads AuthMe's `config.yml` and logs a console warning if
either of those settings is on, so the conflict is caught before players run
into it. Disable them in AuthMe's own config to resolve the warning.

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