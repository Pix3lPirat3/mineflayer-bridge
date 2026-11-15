package io.github.pix3lpirat3.mfbridge.core.session;

/**
 * Polices one connection's inbound rate against its advertised budget:
 * more than {@link #FACTOR} times the budget for {@link #SUSTAIN_SECONDS} seconds in a row
 * earns a {@code rate_limited} error; doing it again before calming down closes the connection.
 * Pure: fed one closed second at a time.
 */
final class RatePolicer {
    /** How far over the budget a second must be to count. */
    static final int FACTOR = 2;
    /** How many such seconds in a row before acting. */
    static final int SUSTAIN_SECONDS = 5;

    enum Verdict { OK, WARN, DISCONNECT }

    private int overSeconds;
    private int calmSeconds;
    private boolean warned;

    /**
     * Reports a closed one-second window.
     *
     * @param messages messages received in it
     * @param bytes bytes received in it
     * @param messageBudget advertised messages per second, for every session of the connection together
     * @param byteBudget advertised bytes per second, likewise
     * @return what to do
     */
    Verdict second(long messages, long bytes, long messageBudget, long byteBudget) {
        boolean over = messages > FACTOR * messageBudget || bytes > FACTOR * byteBudget;
        if (!over) {
            overSeconds = 0;
            if (++calmSeconds >= SUSTAIN_SECONDS) {
                warned = false;
            }
            return Verdict.OK;
        }
        calmSeconds = 0;
        if (++overSeconds < SUSTAIN_SECONDS) {
            return Verdict.OK;
        }
        overSeconds = 0;
        if (warned) {
            return Verdict.DISCONNECT;
        }
        warned = true;
        return Verdict.WARN;
    }
}
