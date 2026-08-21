package com.authmebia.dialog.util;

import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Parses a "custom_icons.<name>" config section describing an inline icon
 * (a sprite from an atlas, a player head, or a raw item/material) and turns
 * it into either a Component prefix -- for title/label text, via
 * Component.object() -- or an ItemDialogBody -- for dialog content, via
 * DialogBody.item().
 *
 * Config shape (all fields optional except "type"):
 *
 * custom_icons:
 *   bedrock:
 *     type: sprite            # sprite | player_head | item
 *     atlas: items             # sprite only, default "items"
 *     sprite: item/bedrock      # sprite only
 *   my_head:
 *     type: player_head         # player_head only, name or {player}
 *     player: "{player}"
 *   red_bed:
 *     type: item                 # item only
 *     material: RED_BED
 *     show_decorations: false    # item only, default true
 *     show_tooltip: false        # item only, default true
 *     width: 16                  # item only, default 16
 *     height: 16                 # item only, default 16
 *
 * Unknown/missing/invalid config is treated as "no icon" everywhere -- an
 * icon is a purely cosmetic addition, so any parsing problem here should
 * silently fall back to the plain text/dialog the caller already builds,
 * never break the dialog itself.
 */
public final class IconSpec {

    public enum Type { SPRITE, PLAYER_HEAD, ITEM }

    private final Type type;
    private final Key atlas;
    private final Key sprite;
    private final String playerNameTemplate;
    private final Material material;
    private final boolean showDecorations;
    private final boolean showTooltip;
    private final int width;
    private final int height;

    private IconSpec(Type type, Key atlas, Key sprite, String playerNameTemplate,
                      Material material, boolean showDecorations, boolean showTooltip,
                      int width, int height) {
        this.type = type;
        this.atlas = atlas;
        this.sprite = sprite;
        this.playerNameTemplate = playerNameTemplate;
        this.material = material;
        this.showDecorations = showDecorations;
        this.showTooltip = showTooltip;
        this.width = width;
        this.height = height;
    }

    /**
     * Parses a single icon definition map (one value under "custom_icons:").
     * Returns null if the map is null/empty or the type/required fields are
     * missing or invalid -- see class javadoc for why this never throws.
     */
    public static IconSpec parse(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return null;
        String typeStr = str(map, "type", "");
        try {
            switch (typeStr.toLowerCase(java.util.Locale.ROOT)) {
                case "sprite": {
                    String spriteStr = str(map, "sprite", null);
                    if (spriteStr == null || spriteStr.isBlank()) return null;
                    Key atlas = Key.key(str(map, "atlas", "items"));
                    Key sprite = Key.key(spriteStr);
                    return new IconSpec(Type.SPRITE, atlas, sprite, null, null,
                            true, true, 16, 16);
                }
                case "player_head": {
                    String player = str(map, "player", "{player}");
                    return new IconSpec(Type.PLAYER_HEAD, null, null, player, null,
                            true, true, 16, 16);
                }
                case "item": {
                    String matStr = str(map, "material", null);
                    if (matStr == null) return null;
                    Material material = Material.matchMaterial(matStr);
                    if (material == null) return null;
                    boolean showDecorations = bool(map, "show_decorations", true);
                    boolean showTooltip = bool(map, "show_tooltip", true);
                    int width = clampSize(intVal(map, "width", 16));
                    int height = clampSize(intVal(map, "height", 16));
                    return new IconSpec(Type.ITEM, null, null, null, material,
                            showDecorations, showTooltip, width, height);
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            // Malformed Key (bad namespace/path characters), bad material
            // name, etc. -- treat exactly like "no icon configured".
            return null;
        }
    }

    /**
     * Builds a Component suitable for prepending to a title or button
     * label (SPRITE and PLAYER_HEAD only). Returns null for ITEM-type
     * specs, since an item cannot be inlined into text -- use
     * toItemBody() instead for those, typically in a dialog's content
     * area.
     */
    public Component toInlineComponent(String playerName) {
        return switch (type) {
            case SPRITE -> Component.object(ObjectContents.sprite(atlas, sprite));
            case PLAYER_HEAD -> {
                String resolved = playerNameTemplate == null ? null
                        : playerNameTemplate.replace("{player}", playerName == null ? "" : playerName);
                if (resolved == null || resolved.isBlank()) yield null;
                yield Component.object(ObjectContents.playerHead(resolved));
            }
            case ITEM -> null;
        };
    }

    /**
     * Builds a dialog body entry for this icon (ITEM type only). Returns
     * null for SPRITE/PLAYER_HEAD specs -- use toInlineComponent() for
     * those instead, typically prepended to a title/label Component.
     */
    public DialogBody toItemBody() {
        if (type != Type.ITEM) return null;
        ItemStack stack = new ItemStack(material);
        ItemDialogBody.Builder builder = DialogBody.item(stack)
                .showDecorations(showDecorations)
                .showTooltip(showTooltip)
                .width(width)
                .height(height);
        return builder.build();
    }

    public Type type() { return type; }

    private static int clampSize(int v) {
        return Math.max(1, Math.min(256, v));
    }

    private static String str(Map<?, ?> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean bool(Map<?, ?> map, String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static int intVal(Map<?, ?> map, String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { return def; }
    }
}
