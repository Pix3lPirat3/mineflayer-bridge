package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.pix3lpirat3.mfbridge.protocol.core.DepthMode;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.EditableField;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.StyleData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldEditorsTest {
    private static final Text EN = EnglishText.INSTANCE;

    private static final LabelData LABEL = new LabelData(new Vec3d(1, 2, 3), "hi", 1f, 0x40000000, 64f, null);

    @Test
    void editorFollowsTheFieldTypeUnlessARequestFits() {
        assertEquals(FieldEditors.Editor.TEXT, FieldEditors.choose("text", String.class, null));
        assertEquals(FieldEditors.Editor.ANVIL, FieldEditors.choose("text", String.class, "anvil"));
        assertEquals(FieldEditors.Editor.TEXT, FieldEditors.choose("text", String.class, "gizmo"), "a misfit request falls back");
        assertEquals(FieldEditors.Editor.GIZMO, FieldEditors.choose("pos", Vec3d.class, null));
        assertEquals(FieldEditors.Editor.COLOR, FieldEditors.choose("background", int.class, null));
        assertEquals(FieldEditors.Editor.COLOR, FieldEditors.choose("lineColor", Integer.class, null));
        assertEquals(FieldEditors.Editor.NUMBER, FieldEditors.choose("count", int.class, null));
        assertEquals(FieldEditors.Editor.COLOR, FieldEditors.choose("count", int.class, "color"));
        assertEquals(FieldEditors.Editor.NUMBER, FieldEditors.choose("scale", float.class, null));
        assertEquals(FieldEditors.Editor.TOGGLE, FieldEditors.choose("shadow", boolean.class, null));
        assertEquals(FieldEditors.Editor.CYCLE, FieldEditors.choose("shadow", boolean.class, "cycle"));
        assertEquals(FieldEditors.Editor.CYCLE, FieldEditors.choose("depth", DepthMode.class, null));
        assertNull(FieldEditors.choose("tags", List.class, "text"));
    }

    @Test
    void fieldsResolveNamesToIndicesAndSkipUnsupported() {
        List<FieldEditors.Field> f = FieldEditors.fields(LABEL, List.of(new EditableField("text", null, "Caption"),
                new EditableField("pos"), new EditableField("missing"), new EditableField("background")));
        assertEquals(3, f.size());
        assertEquals("Caption", f.get(0).label());
        assertEquals(1, f.get(0).index());
        assertEquals(FieldEditors.Editor.GIZMO, f.get(1).editor());
        assertEquals(0, f.get(1).index());
        assertEquals(FieldEditors.Editor.COLOR, f.get(2).editor());
        DisplayTextData d = new DisplayTextData(new Vec3d(0, 0, 0), "t");
        assertEquals(List.of(), FieldEditors.fields(d, List.of(new EditableField("group"), new EditableField("transform"))));
    }

    @Test
    void withConvertAndNextBuildTheEditedRecord() {
        List<FieldEditors.Field> f = FieldEditors.fields(LABEL, List.of(new EditableField("text"), new EditableField("scale"),
                new EditableField("background")));
        Object next = FieldEditors.with(EN, LABEL, Map.of("text", FieldEditors.convert(EN, f.get(0), "bye"),
                "scale", FieldEditors.convert(EN, f.get(1), 2L), "background", FieldEditors.convert(EN, f.get(2), "#ff0000")));
        assertEquals(new LabelData(new Vec3d(1, 2, 3), "bye", 2f, 0xFFFF0000, 64f, null), next);
        StyleData style = new StyleData(0xFFFFFFFF, 0f, 2f, DepthMode.TEST, 0.35f, false);
        List<FieldEditors.Field> s = FieldEditors.fields(style, List.of(new EditableField("depth"), new EditableField("pulse")));
        assertEquals(DepthMode.XRAY, FieldEditors.next(s.get(0)));
        assertEquals(true, FieldEditors.next(s.get(1)));
        assertEquals(DepthMode.BOTH, FieldEditors.convert(EN, s.get(0), "both"));
        assertThrows(IllegalArgumentException.class, () -> FieldEditors.convert(EN, f.get(1), "wide"));
    }

    @Test
    void coloursAndScrollSteps() {
        assertEquals(0xFF112233, FieldEditors.parseColor(EN, "#112233"));
        assertEquals(0x40112233, FieldEditors.parseColor(EN, "40112233"));
        assertEquals("#112233", FieldEditors.colorText(0xFF112233));
        assertEquals("#40112233", FieldEditors.colorText(0x40112233));
        assertThrows(IllegalArgumentException.class, () -> FieldEditors.parseColor(EN, "#12"));
        assertEquals("6", FieldEditors.scrolled("5", 1, true));
        assertEquals("1.4", FieldEditors.scrolled("1.5", -1, false));
        assertEquals("x", FieldEditors.scrolled("x", 1, true));
    }
}
