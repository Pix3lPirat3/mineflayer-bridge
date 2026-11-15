package io.github.pix3lpirat3.mfbridge.api.client;

import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Bridge home tab an extension adds, built from the same screen model bots use.
 * {@link #rows} runs on every refresh of the open screen, so it reads live state.
 */
public interface HomeTab {

    /**
     * Returns the tab's rows now.
     *
     * @return the rows; interactive items need ids, which {@link #use} receives
     */
    List<ScreenRow> rows();

    /**
     * The player used an item of the tab.
     *
     * @param itemId the item id
     * @param value null for a click, the new {@code Boolean} of a toggle or the {@code Long} index of a cycle
     */
    void use(String itemId, @Nullable Object value);
}
