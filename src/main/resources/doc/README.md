# AuthMeBia — Documentation

---

## MiniMessage Formatting

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

## Password Recovery ("Forgot Password?")

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
2. If the account has no email , the player sees a message telling
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
dialogs (captcha, 2FA, recover, rule) are unaffected and keep their own
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

Three independent settings protect against repeated wrong-password and
wrong-code attempts. `login_attempts` and `otp_attempts` are enabled by
default; `ip_ban` is opt-in (disabled by default).

### Login attempt limit (`login_attempts`)

Kicks the player after too many wrong passwords within a single login dialog
session (the counter resets on each new connection). Enabled by default.

| Key | Description |
|-----|-------------|
| `enabled` | Master switch. Default: `true` |
| `max_tries` | Number of wrong attempts allowed before a kick (minimum 1) |

The kick uses the `disconnect.too_many_attempts` message from the active
language file.

### OTP / 2FA attempt limit (`otp_attempts`)

Same idea as `login_attempts`, but for one-time codes: the 2FA (TOTP) prompt
shown after a correct password, the forgot-password email code, and the
registration email code. Kicks the player after too many wrong codes within
that session. Enabled by default.

| Key | Description |
|-----|-------------|
| `enabled` | Master switch. Default: `true` |
| `max_tries` | Number of wrong code attempts allowed before a kick (minimum 1) |

Uses the same `disconnect.too_many_attempts` message as `login_attempts`.

### IP ban (`ip_ban`)

Tracks wrong-password and wrong-OTP/2FA-code attempts per IP address across
sessions (not just one dialog), and issues a temporary Bukkit IP ban once
the threshold is crossed. Repeated offenses from the same IP escalate to
longer ban durations.

| Key | Description |
|-----|-------------|
| `enabled` | Master switch |
| `threshold` | Wrong attempts from the same IP before the first ban (minimum 1) |
| `ban_durations_seconds` | List of ban lengths in seconds, one per escalation level; the last value repeats for any further offense |

The default escalation is `[600, 1800, 3600, 86400]` — 10 minutes, 30
minutes, 1 hour, then 1 day for every offense after that. The ban reason
shown to the player uses the `disconnect.ip_banned` message, with
`{player_ip}` filled in.

All counters are tracked in memory and reset when the plugin restarts.

---

## Custom Icons (`<icons:name>`)

Any MiniMessage string anywhere in `config.yml` (dialog titles, content,
button labels, custom screen text, etc.) can show a small inline icon by
placing the tag `<icons:name>` wherever you want it to appear — the tag is
replaced in place, so position in the string is entirely up to you:

```yaml
title: "<icons:trial_key> <gold>Login</gold>"
title: "<gold>Login</gold> <icons:trial_key>"
content: "Welcome!\n<icons:trial_key> Read the rules below."
```

Each icon is defined once under `custom_icons:` in `config.yml`, keyed by the
name used after `icons:` in the tag:

```yaml
custom_icons:
  trial_key:
    type: sprite
    atlas: items
    sprite: item/trial_key
```

### Icon types

| `type` | Description | Fields |
|--------|-------------|--------|
| `sprite` | An icon from a texture atlas — the same kind of icon vanilla uses for items/effects in its own UI. | `atlas` (default `items`), `sprite` (e.g. `item/trial_key`) |
| `player_head` | A player's head. | `player` (default `{player}`, the viewing player) |
| `item` | A full item icon, including custom model data/enchant glow via the item itself. | `material` (a Bukkit `Material` name), `show_decorations` (default `true`), `show_tooltip` (default `true`), `width`/`height` (default `16`) |

Only `sprite` and `player_head` icons can be placed inline via
`<icons:name>` — an `item` icon has no inline text form, so `<icons:name>`
for an `item`-type entry resolves to nothing wherever it's placed. `item`
icons are only usable through a custom screen's `icon:` field instead (see
Custom Screens below), which shows them as a small icon above the dialog's
content.

Anything invalid or missing — an unknown name, a bad sprite/material, etc. —
is silently treated as "no icon", so a bad tag or a bad entry never breaks
the surrounding text or the dialog itself.

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
| `title` | string | `"Notice"` | Dialog title. Full MiniMessage, can include `<icons:name>` (see Custom Icons below). |
| `content` | string | `""` | Dialog body. Full MiniMessage + `\n`. |
| `allow_close` | boolean | `true` | Whether ESC/click-outside closes the dialog |
| `button_width` | int | `dialog.button_width` | Default width for buttons in this screen |
| `trigger` | string | `command` | When the screen auto-shows (see below) |
| `sound_on_show` | string | `""` | Sound played when the screen opens (in-game only) |
| `icon` | string | none | Name of an `item`-type entry under `custom_icons:` in `config.yml`, shown as a small icon above the dialog's content. Only `item`-type icons are usable here — `sprite`/`player_head` icons go inline in `title`/`content` instead, via `<icons:name>`. |
| `checkbox_label` | string | none | If set, shows a checkbox with this label (MiniMessage + `{player}`). Leave unset for no checkbox. |
| `checkbox_action` | string | `show` | What ticking `checkbox_label` does when the player dismisses the screen — see below. Only takes effect for `postjoin`/`prejoin` triggers; `/bia screen <id>` always shows the screen regardless. |

### `checkbox_action` values

| Value | Behaviour |
|-------|-----------|
| `show` (default) | Purely cosmetic — the screen shows again next time it's triggered either way. |
| `close` | Ticked = never show this screen to this player again (persisted, survives restarts). Left unticked = shows again next time, same as `show`. |

`checkbox_action: close` only fires from a `close`, `command`, or `console`
button — `open_url` and `copy` are client-only actions that never send the
checkbox's value back to the server, so a checkbox on a screen with only
those button types can never actually trigger it. Use `/bia screen reset
<player> [id]` to clear a player's "closed forever" checkbox for a screen
(omit `id` to reset all screens for that player).


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

### Showing a screen command

```
/bia screen <id>            Show screen to yourself (must be in-game)
/bia screen <id> <player>  Show screen to another online player
/bia screen reset <player>          Reset every screen's "closed forever" checkbox for a player
/bia screen reset <player> <id>     Reset just one screen's "closed forever" checkbox for a player
```

Tab completion is available for `<id>` (lists all configured screen IDs) and
`<player>` (lists online players). The same tab completion applies to
`/bia add <player>`, `/bia rm <player>`, and `/bia recover <player>`.
`/bia screen reset` requires OP, same as `/bia screen`.

---

## Toast Notifications

AuthMeBia can show the small vanilla advancement-style toast popup in the
corner of the screen on certain player milestones. Each toast is defined in
`notifications.toasts` in `config.yml`.

### Defining a advencement 

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

### fields

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
### Previewing a advencement custom(`/bia notifier`)

```
/bia notifier <toast_name> <player> show [seconds]
```

Shows custom title advenment to an online player for testing/fun.So preview a
toast never affects whether it still fires for real later. `[seconds]`
optionally overrides the configured `delay` for this preview only.

---

## Old Client Compatibility

The Dialog UI used for register/login menus only exists from
Minecraft 1.21.6 (protocol 771) onward. Clients on an older version
(connecting through ViaVersion ) cannot render a dialog packet at
all |  if one is sent anyway, the connection just sits there until the
server's own network read-timeout eventually kicks the player, which looks
like the connection freezing before a disconnect.

AuthMeBia checks each connecting client's protocol version, on both the
pre-spawn (`dialog.menu: true`) and post-spawn (`dialog.menu: false`) paths,
before ever sending a dialog. Clients below `dialog.min_protocol_version`
skip every AuthMeBia dialog and authenticate with AuthMe's plain `/login`
and `/register` commands instead -- the freeze-then-kick never happens.
See the `dialog.min_protocol_version` comment in `config.yml` for details.

---

### AuthMe dialog check

AuthMe itself has its own optional native dialog feature
(`settings.registration.dialog.preJoin.enable` /
`settings.registration.dialog.postJoin.enable` in AuthMe's `config.yml`). If
that is left enabled alongside AuthMeBia's dialogs, players would see two
separate dialog systems fighting over the same login/register flow. On
startup, AuthMeBia reads AuthMe's `config.yml` and logs a console warning if
either of those settings is on, so the conflict is caught before players run
into it. Disable them in AuthMe's own config to resolve the warning.

---

## Bypass List (`data/<uuid>/data.yml`)

Some players need to skip every authmebia dialog same pre-spawn / post-spawn  
authenticate with AuthMe's own command `/login` and `/register`.

### Commands

| Command | Permission | Effect                                         |
|---------|------------|------------------------------------------------|
| `/bia add <player>` or `/authmebia add <player>` | `authmebia.bypass` | Adds the player to the bypass dialog list   |
| `/bia rm <player>` or `/authmebia rm <player>` | `authmebia.bypass` | Removes the player from the bypass dialog list |

The target player must either be online right now, or have joined this
server before (so the server already has their UUID cached). New players
who have never connected cannot be added by name in advance.

### Storage format

All per-player data (bypass list, admin-forced recovery, shown toasts,
dismissed custom screens) is merged into a single file at:

```
plugins/AuthMeBia/data/<uuid>/data.yml
```

```yaml
name: Dragonlucky
uuid: 069a79f4-44e9-4726-a5be-fca90e38aaf5
added: "2067-09-22T10:15:30Z"
bypass: true
recover: false
toasts_shown:
  - first_login
dismissed:
  - welcome_notice
```

| Field | Description |
|-------|--------------|
| `name` | The player's name at the time they were last added/flagged |
| `uuid` | The player's UUID (also the folder name) |
| `added` | UTC timestamp (ISO-8601) of when the bypass entry was created |
| `bypass` | Whether the player is on the bypass list |
| `recover` / `recover_requested` | Admin-forced password reset flag and its timestamp |
| `toasts_shown` | List of toast names already shown to the player |
| `dismissed` | List of custom-screen ids the player has permanently dismissed |

This file used to be split across three separate files (`player.yml`,
`toasts.yml`, `dismissed_screens.yml`) under the same `data/<uuid>/`
folder. If any of those legacy files are still present when a player's
data is next read, AuthMeBia automatically merges them into `data.yml`
in place -- nothing is lost, and the old files are left on disk (unused)
rather than deleted, purely as a safety margin.

---

## Welcome (`welcome.json`)

The welcome is generated when a player registers and (optionally) posted
to wedhook(discord). It is built from canvas in `welcome.json`.

Enable the feature in `config.yml`:

```yaml
welcome_image:
  enabled: true
```

If `discord.enabled` is `true` and a `discord.webhook_url` is set, the image is
also sent to that webhook.

### fields

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