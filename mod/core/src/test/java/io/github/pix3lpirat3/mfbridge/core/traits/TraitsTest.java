package io.github.pix3lpirat3.mfbridge.core.traits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.EditableField;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.HoverStyle;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Visibility;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraitsTest {
    private static final Interactive OWN = new Interactive(List.of(ElementInput.PRIMARY));
    private static final Interactive TRAIT = new Interactive(List.of(ElementInput.SECONDARY));

    private static DisplayTextData display(Interactive interactive, HoverStyle hover) {
        return new DisplayTextData(new Vec3d(0, 0, 0), "t", 200, 0, false, false, "center", 1f, null, null, "center", 0,
                null, 0f, 0, null, null, hover, interactive, 64f);
    }

    @Test
    void traitsWinOverTheKindAliases() {
        HoverStyle ownHover = new HoverStyle(2f, null, null);
        DisplayTextData d = display(OWN, ownHover);
        assertSame(OWN, Traits.interactive(null, d));
        assertSame(ownHover, Traits.hover(new ObjectTraits(), d));
        HoverStyle traitHover = new HoverStyle(null, 0xFF00FF00, null);
        ObjectTraits t = new ObjectTraits(TRAIT, traitHover, null, null, null);
        assertSame(TRAIT, Traits.interactive(t, d));
        assertSame(traitHover, Traits.hover(t, d));
    }

    @Test
    void anyKindCanBeInteractiveOrTargetable() {
        BoxData box = new BoxData(new Vec3d(0, 0, 0), new Vec3d(1, 1, 1), null);
        assertNull(Traits.interactive(null, box));
        assertFalse(Traits.targetable(null, box));
        assertSame(TRAIT, Traits.interactive(new ObjectTraits(TRAIT, null, null, null, null), box));
        assertTrue(Traits.targetable(new ObjectTraits(null, null, List.of(new EditableField("min")), null, null), box));
        assertTrue(Traits.targetable(new ObjectTraits(null, null, null, List.of("open"), null), box));
        assertTrue(Traits.targetable(new ObjectTraits(null, null, null, null, new Visibility(null, null, false, true)), box));
        assertTrue(Traits.targetable(new ObjectTraits(null, new HoverStyle(null, 0x00FF00, null), null, null, null), box), "a hover glow needs the raycast");
        assertEquals(List.of(), Traits.editable(null));
        assertEquals(List.of(), Traits.actions(new ObjectTraits()));
    }

    @Test
    void visibilityChecksDistanceRangeAndLookedAt() {
        Visibility range = new Visibility(2f, 10f, false, false);
        assertFalse(Traits.shown(range, 1, false));
        assertTrue(Traits.shown(range, 4, false));
        assertTrue(Traits.shown(range, 100, false));
        assertFalse(Traits.shown(range, 100.5, false));
        assertTrue(Traits.shown(range, Double.NaN, false), "unknown distance passes");
        Visibility looked = new Visibility(null, null, false, true);
        assertFalse(Traits.shown(looked, 1, false));
        assertTrue(Traits.shown(looked, 1, true));
        assertTrue(Traits.shown(null, 1e9, false));
        assertTrue(Traits.perFrame(range));
        assertFalse(Traits.perFrame(new Visibility(null, null, true, false)));
        assertTrue(Traits.focusedOnly(new ObjectTraits(null, null, null, null, new Visibility(null, null, true, false))));
    }
}
