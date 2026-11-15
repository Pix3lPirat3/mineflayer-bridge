package io.github.pix3lpirat3.mfbridge.common.render.retained;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pix3lpirat3.mfbridge.core.retained.DiffClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * The parsed palette of one ghost volume and the diff rule against world states
 * ({@code diffAgainstWorld}). Built on a worker; immutable afterwards and shared by mesh tasks.
 *
 * <p>Only the properties a palette entry names are compared, so {@code minecraft:oak_stairs} matches
 * stairs facing any way while {@code minecraft:oak_stairs[facing=east]} does not.
 */
final class GhostStates implements DiffClassifier.StateMatcher {
    private final @Nullable BlockState[] states;
    private final List<Map<Property<?>, Comparable<?>>> named;

    GhostStates(final List<String> palette) {
        final int n = palette.size();
        this.states = new BlockState[n];
        this.named = new ArrayList<>(n);
        named.add(Map.of());
        for (int i = 1; i < n; i++) {
            try {
                final BlockStateParser.BlockResult r = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, palette.get(i), false);
                states[i] = r.blockState();
                named.add(r.properties());
            } catch (final CommandSyntaxException | RuntimeException e) {
                // Unknown or malformed states draw nothing and never count as a difference.
                states[i] = null;
                named.add(Map.of());
            }
        }
    }

    /** Returns the state of a palette id, or null when unset or unparseable. */
    @Nullable BlockState state(final int paletteId) {
        return paletteId > 0 && paletteId < states.length ? states[paletteId] : null;
    }

    @Override
    public byte classify(final int paletteId, final int worldStateId) {
        final BlockState wanted = state(paletteId);
        final BlockState world = Block.BLOCK_STATE_REGISTRY.byId(worldStateId);
        if (wanted == null || world == null) {
            return DiffClassifier.PLAIN;
        }
        final boolean same = wanted.getBlock() == world.getBlock();
        return DiffClassifier.rule(wanted.isAir(), world.isAir(), same, same && namedMatch(named.get(paletteId), world));
    }

    private static boolean namedMatch(final Map<Property<?>, Comparable<?>> props, final BlockState world) {
        for (final Map.Entry<Property<?>, Comparable<?>> e : props.entrySet()) {
            if (!world.hasProperty(e.getKey()) || !world.getValue(e.getKey()).equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }
}
