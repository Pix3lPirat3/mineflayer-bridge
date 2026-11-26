package io.github.pix3lpirat3.mfbridge.common.ui.container;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Applies a slot item's data components to a display stack, so the game draws it as its own: the
 * durability bar, glint, trims, dye and potion colours, bundle fullness, and every tooltip line.
 * Components are keyed by id in the game's saved form, {@code {"minecraft:damage": 10}}; a key
 * {@code !minecraft:food} removes one the item has by default. A component this game does not know
 * or cannot read (an enchantment the server lacks, a field another version names differently) is
 * skipped on its own, the rest still apply.
 */
public final class ItemComponents {

    private static final int MAX_DEPTH = 32;

    private ItemComponents() {
    }

    /**
     * Applies components to a stack. Does nothing outside a world, where the game has no registries
     * to read them against.
     *
     * @param stack the stack to change
     * @param components a map of component id to value, as the wire carries it; anything else is ignored
     */
    public static void apply(final ItemStack stack, final @Nullable Object components) {
        if (components instanceof Map<?, ?> map && !map.isEmpty()) {
            apply(stack, map, registries());
        }
    }

    /**
     * Applies components read against the given registries.
     *
     * @param stack the stack to change
     * @param map component id to value
     * @param registries the registries entries are named in, or null to apply nothing
     */
    static void apply(final ItemStack stack, final Map<?, ?> map, final HolderLookup.@Nullable Provider registries) {
        if (registries == null) {
            return;
        }
        final DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        for (final Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                continue;
            }
            final boolean remove = key.startsWith("!");
            final Identifier id = Identifier.tryParse(remove ? key.substring(1) : key);
            final DataComponentType<?> type = id == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
            if (type == null) {
                continue;
            }
            if (remove) {
                stack.remove(type);
            } else {
                set(stack, type, json(e.getValue(), 0), ops);
            }
        }
    }

    private static <T> void set(final ItemStack stack, final DataComponentType<T> type, final JsonElement value, final DynamicOps<JsonElement> ops) {
        final Codec<T> codec = type.codec();
        if (codec == null) {
            return;
        }
        try {
            codec.parse(ops, value).result().ifPresent(v -> stack.set(type, v));
        } catch (final RuntimeException skipped) {
            // A value the codec trips over is left out like one it refuses.
        }
    }

    private static HolderLookup.@Nullable Provider registries() {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientPacketListener connection = minecraft == null ? null : minecraft.getConnection();
        return connection == null ? null : connection.registryAccess();
    }

    /**
     * Converts a value read from the wire (maps, lists, strings, numbers, booleans) to JSON.
     *
     * @param value the value
     * @param depth how deep it is nested
     * @return the JSON form
     */
    static JsonElement json(final @Nullable Object value, final int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof BigInteger n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof Map<?, ?> map) {
            final JsonObject out = new JsonObject();
            for (final Map.Entry<?, ?> e : map.entrySet()) {
                out.add(String.valueOf(e.getKey()), json(e.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            final JsonArray out = new JsonArray(list.size());
            for (final Object v : list) {
                out.add(json(v, depth + 1));
            }
            return out;
        }
        if (value instanceof byte[] bytes) {
            final JsonArray out = new JsonArray(bytes.length);
            for (final byte b : bytes) {
                out.add(b);
            }
            return out;
        }
        return JsonNull.INSTANCE;
    }
}
