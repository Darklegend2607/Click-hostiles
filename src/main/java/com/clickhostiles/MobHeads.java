package com.clickhostiles;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Picks the head item that represents a captured mob. */
public final class MobHeads {
    /** entity id path (e.g. "blaze") -> texture hash on textures.minecraft.net */
    private static final Map<String, String> HASHES = new HashMap<>();
    private static final Map<EntityType<?>, Item> VANILLA = new HashMap<>();

    static {
        VANILLA.put(EntityType.ZOMBIE, Items.ZOMBIE_HEAD);
        VANILLA.put(EntityType.GIANT, Items.ZOMBIE_HEAD);
        VANILLA.put(EntityType.SKELETON, Items.SKELETON_SKULL);
        VANILLA.put(EntityType.WITHER_SKELETON, Items.WITHER_SKELETON_SKULL);
        VANILLA.put(EntityType.CREEPER, Items.CREEPER_HEAD);
        VANILLA.put(EntityType.PIGLIN, Items.PIGLIN_HEAD);
        VANILLA.put(EntityType.ENDER_DRAGON, Items.DRAGON_HEAD);
    }

    private MobHeads() {}

    public static void load() {
        try (InputStream in = MobHeads.class.getResourceAsStream("/clickhostiles_heads.json")) {
            if (in == null) return;
            JsonObject obj = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            obj.entrySet().forEach(e -> HASHES.put(e.getKey(), e.getValue().getAsString()));
        } catch (Exception e) {
            ClickHostiles.LOGGER.warn("Could not load head textures, falling back to spawn eggs", e);
        }
    }

    /** Fresh head stack for this mob type: vanilla head, textured player head, or spawn egg as last resort. */
    public static ItemStack headFor(EntityType<?> type) {
        Item vanilla = VANILLA.get(type);
        if (vanilla != null) return new ItemStack(vanilla);

        String hash = HASHES.get(EntityType.getId(type).getPath());
        if (hash != null) {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/" + hash + "\"}}}";
            String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            PropertyMap props = new PropertyMap();
            props.put("textures", new Property("textures", value));
            UUID id = UUID.nameUUIDFromBytes(("clickhostiles:" + hash).getBytes(StandardCharsets.UTF_8));

            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponentTypes.PROFILE, new ProfileComponent(Optional.empty(), Optional.of(id), props));
            return head;
        }

        SpawnEggItem egg = SpawnEggItem.forEntity(type);
        return egg != null ? new ItemStack(egg) : new ItemStack(Items.ZOMBIE_HEAD);
    }
}
