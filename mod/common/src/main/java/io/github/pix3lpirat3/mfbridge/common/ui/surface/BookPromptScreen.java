package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptScreen;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The {@code book} surface: a book-and-quill page editor with the
 * vanilla book texture, page buttons and edit box. Vanilla's {@code BookEditScreen} sends {@code
 * ServerboundEditBookPacket} from private button handlers, so this is a separate screen modelled on
 * it (26.3 layout); it holds no item and builds no packet. Done answers a pages prompt with the pages
 * and a text prompt with the pages joined by blank lines. Esc returns to the prompt card. Client
 * thread only.
 */
public final class BookPromptScreen extends PromptScreen {

    private static final int IMAGE_SIZE = 192;
    private static final int MAX_PAGES = 100;
    private static final int TEXT_ARGB = 0xFF000000;

    private final List<String> pages = new ArrayList<>();
    private int current;
    private MultiLineEditBox page;
    private PageButton backButton;
    private Component pageIndicator = CommonComponents.EMPTY;

    /**
     * Creates the screen.
     *
     * @param prompt a text or pages prompt
     * @param controller where the answer goes: the prompt controller or a local editor
     */
    public BookPromptScreen(final PromptInput prompt, final PromptAnswers controller) {
        super(Component.literal(prompt.request().text()), prompt, controller);
        final Object def = prompt.request().defaultValue();
        if (def instanceof List<?> list) {
            for (final Object o : list) {
                pages.add(String.valueOf(o));
            }
        } else if (def != null) {
            pages.add(def.toString());
        }
        if (pages.isEmpty()) {
            pages.add("");
        }
        this.page = MultiLineEditBox.builder().build(Minecraft.getInstance().font, 122, 134, CommonComponents.EMPTY);
        this.backButton = new PageButton(0, 0, false, b -> { }, false);
    }

    private int left() {
        return (width - IMAGE_SIZE) / 2;
    }

    @Override
    protected void init() {
        final int left = left();
        final int top = 2;
        final Integer maxLength = prompt.request().maxLength();
        page = MultiLineEditBox.builder()
                .setShowDecorations(false)
                .setTextColor(TEXT_ARGB)
                .setCursorColor(TEXT_ARGB)
                .setShowBackground(false)
                .setTextShadow(false)
                .setX((width - 114) / 2 - 8)
                .setY(28)
                .build(font, 122, 134, CommonComponents.EMPTY);
        page.setCharacterLimit(maxLength == null ? 1024 : Math.max(1, maxLength));
        page.setLineLimit(126 / 9);
        page.setValueListener(value -> pages.set(current, value));
        addRenderableWidget(page);
        backButton = addRenderableWidget(new PageButton(left + 43, top + 157, false, b -> turn(-1), true));
        addRenderableWidget(new PageButton(left + 116, top + 157, true, b -> turn(1), true));
        final int buttons = top + IMAGE_SIZE + 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit()).pos(width / 2 - 100, buttons).width(98).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).pos(width / 2 + 2, buttons).width(98).build());
        showPage();
    }

    @Override
    protected void setInitialFocus() {
        setInitialFocus(page);
    }

    private void turn(final int delta) {
        final int next = current + delta;
        if (next < 0) {
            return;
        }
        if (next >= pages.size()) {
            if (pages.size() >= MAX_PAGES) {
                return;
            }
            pages.add("");
        }
        current = next;
        showPage();
    }

    private void showPage() {
        page.setValue(pages.get(current), true);
        pageIndicator = Component.translatable("book.pageIndicator", current + 1, pages.size()).withColor(TEXT_ARGB).withoutShadow();
        backButton.visible = current > 0;
    }

    private void submit() {
        final List<String> out = new ArrayList<>(pages);
        while (out.size() > 1 && out.getLast().isEmpty()) {
            out.removeLast();
        }
        minecraft.gui.setScreen(null);
        if (prompt.request().kind() == PromptKind.TEXT) {
            controller.answer(String.join("\n\n", out));
        } else {
            controller.answer(List.copyOf(out));
        }
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BookViewScreen.BOOK_LOCATION, left(), 2, 0f, 0f, IMAGE_SIZE, IMAGE_SIZE, 256, 256);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.text(font, pageIndicator, left() + 148 - font.width(pageIndicator), 2 + 16, TEXT_ARGB, false);
        drawPromptLine(graphics, IMAGE_SIZE + 26);
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }
}
