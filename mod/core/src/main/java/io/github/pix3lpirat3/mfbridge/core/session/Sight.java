package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import org.jspecify.annotations.Nullable;

/** What the player can see right now: which sessions are attached and which worlds match. */
interface Sight {
    /** Returns whether the player currently has this session attached. */
    boolean attachedNow(Session s);

    /** Returns whether the objects of an owner (no trailing slash) are shown at all. */
    boolean ownerVisible(String owner);

    /** {@code visibleWhen.focusedOnly} objects show only while their bot is focused. */
    boolean focusAllows(String owner, @Nullable ObjectTraits traits);

    /** Returns whether a world-scoped object in world {@code w} is in the player's world. */
    boolean worldVisible(@Nullable WorldKey w);
}
