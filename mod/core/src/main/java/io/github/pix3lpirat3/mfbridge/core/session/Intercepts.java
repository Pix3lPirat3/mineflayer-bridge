package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.ApprovalDecision;
import io.github.pix3lpirat3.mfbridge.core.decl.InterceptDecl;
import io.github.pix3lpirat3.mfbridge.core.input.InterceptControls;
import io.github.pix3lpirat3.mfbridge.core.input.InterceptOutcome;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.InterceptEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** The intercept state behind {@link InterceptControls}. */
@ClientThread
final class Intercepts implements InterceptControls {
    private static final Comparator<InterceptDecl> ORDER =
            Comparator.comparingInt(InterceptDecl::rank).thenComparing(InterceptDecl::key);

    private final SessionHost host;
    private final Interactions interactions;
    private final PlayerPrefs prefs;
    /** Answers to the permission prompt in this run, by identity; never saved. */
    private final Map<String, Boolean> answered = new HashMap<>();
    private boolean paused;

    Intercepts(SessionHost host, Interactions interactions, PlayerPrefs prefs) {
        this.host = host;
        this.interactions = interactions;
        this.prefs = prefs;
    }

    @Override
    public List<InterceptDecl> matching(String input, int modifiers, Predicate<TargetSpec> target) {
        if (paused) {
            return List.of();
        }
        List<InterceptDecl> out = new ArrayList<>();
        for (InterceptDecl d : host.declarations().intercepts()) {
            if (!d.data().input().equalsIgnoreCase(input)) {
                continue;
            }
            int bit = d.modifierBit();
            if (bit < 0 || (modifiers & bit) != bit) {
                continue;
            }
            if (target.test(d.data().target())) {
                out.add(d);
            }
        }
        out.sort(ORDER);
        return List.copyOf(out);
    }

    @Override
    public InterceptOutcome dispatch(List<InterceptDecl> rules, Function<String, @Nullable Target> targets) {
        if (paused || rules.isEmpty()) {
            return InterceptOutcome.NONE;
        }
        InterceptDecl cancelledBy = null;
        CompletableFuture<InvocationResult> result = null;
        int delivered = 0;
        for (InterceptDecl d : rules) {
            Session s = owner(d.key());
            Target target = targets.apply(d.data().target().type());
            if (s == null || target == null) {
                continue;
            }
            int id = interactions.allocate();
            boolean earlier = cancelledBy != null;
            if (!earlier && d.wantsCancel() && mayCancel(s, d.ownerIdentity())) {
                cancelledBy = d;
                result = interactions.track(s, id);
            }
            host.sendReliable(s, CoreMessages.INTERCEPT_EVENT, new InterceptEvent(d.key(), id, target, earlier), 0,
                    Envelope.NONE);
            delivered++;
        }
        return new InterceptOutcome(cancelledBy, result, delivered);
    }

    /** The live, attached owner of a rule, or null when its event should be dropped. */
    private @Nullable Session owner(String key) {
        Session s;
        try {
            s = host.writer(ObjectKeys.parse(key).owner());
        } catch (BridgeException e) {
            return null;
        }
        return s == null || s.superseded || !host.attachedNow(s) ? null : s;
    }

    @Override
    public boolean paused() {
        return paused;
    }

    @Override
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    @Override
    public @Nullable Boolean cancelPermission(String identity) {
        return prefs.interceptCancel().get(identity);
    }

    @Override
    public void setCancelPermission(String identity, @Nullable Boolean allowed) {
        Boolean previous = allowed == null ? prefs.interceptCancel().remove(identity) : prefs.interceptCancel().put(identity, allowed);
        answered.remove(identity);
        if (!Objects.equals(previous, allowed)) {
            prefs.save();
        }
    }

    @Override
    public boolean mayCancel(@Nullable String identity) {
        return identity != null && mayCancel(host.writer("bot:" + identity), identity);
    }

    private boolean mayCancel(@Nullable Session owner, @Nullable String identity) {
        if (identity == null) {
            return false;
        }
        Boolean saved = prefs.interceptCancel().get(identity);
        if (saved != null) {
            return saved;
        }
        // input.intercept is on for bots of local devices; remote bots need the player's grant.
        if (owner != null && host.localDevice(owner.deviceId)) {
            return true;
        }
        return Boolean.TRUE.equals(answered.get(identity));
    }

    /** Returns whether the player saved a cancel choice for a bot or answered its prompt in this run. */
    boolean decided(String identity) {
        return prefs.interceptCancel().containsKey(identity) || answered.containsKey(identity);
    }

    /**
     * Returns the first cancel rule of each remote bot that has neither a saved choice nor an answer
     * in this run, with the bot's session, in key order.
     */
    List<Map.Entry<Session, InterceptDecl>> unansweredCancels() {
        List<Map.Entry<Session, InterceptDecl>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (InterceptDecl d : host.declarations().intercepts()) {
            String identity = d.ownerIdentity();
            if (identity == null || !d.wantsCancel() || decided(identity) || !seen.add(identity)) {
                continue;
            }
            Session s = owner(d.key());
            if (s != null && !host.localDevice(s.deviceId)) {
                out.add(Map.entry(s, d));
            }
        }
        return out;
    }

    @Override
    public void answerPermission(String identity, ApprovalDecision decision) {
        switch (decision) {
            case ALLOW -> setCancelPermission(identity, Boolean.TRUE);
            case ALLOW_ONCE -> answered.put(identity, Boolean.TRUE);
            case DENY -> answered.put(identity, Boolean.FALSE);
        }
    }
}
