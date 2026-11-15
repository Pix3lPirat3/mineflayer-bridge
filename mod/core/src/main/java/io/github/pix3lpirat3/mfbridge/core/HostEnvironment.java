package io.github.pix3lpirat3.mfbridge.core;

import org.slf4j.Logger;

/**
 * What the loader glue tells the core about the running game, and the callbacks the core needs from
 * it.
 *
 * @param modVersion mod version, advertised in {@code welcome}
 * @param gameVersion Minecraft version, advertised in {@code welcome}
 * @param loader loader name such as {@code fabric} or {@code neoforge}
 * @param approver asks the player about unknown devices
 * @param notifier shows toasts requested by bots
 * @param logger logger for the bridge; hot paths never log
 */
public record HostEnvironment(
        String modVersion,
        String gameVersion,
        String loader,
        DeviceApprover approver,
        Notifier notifier,
        Logger logger) {}
