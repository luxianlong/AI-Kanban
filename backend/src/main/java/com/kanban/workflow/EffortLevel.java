package com.kanban.workflow;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How hard one node's Claude session thinks — the CLI's {@code --effort} levels.
 *
 * <p>{@link #LOW} through {@link #MAX} are one scale, ascending. {@link #ULTRACODE} sits
 * at the end of the list but is not a sixth notch on that scale — see its own note.
 *
 * <p>This is per <em>node</em> because the steps of a workflow are not the same kind
 * of work. A step that rewrites a paragraph it was handed does not need what a step
 * that has to reason its way to an answer needs, and paying the same for both is what
 * "one effort for the whole graph" costs. An A/B of the same prompt measured 170
 * output tokens at {@link #LOW} against 386 at {@link #MAX}.
 *
 * <p><strong>Absent means default.</strong> A node with no level set is run with no
 * {@code --effort} flag at all, so it gets whatever the CLI's own default is — which
 * is deliberately not spelled out as a sixth constant here, because then a release
 * that moved the default would leave every stored node claiming the old one.
 */
public enum EffortLevel {

    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,

    /**
     * Not "one harder than {@link #MAX}". Ultracode is xhigh thinking <em>plus</em> a
     * standing instruction to orchestrate dynamic workflows, so it changes how many
     * sessions a step is rather than only how long one of them reasons — which is why
     * it is worth choosing per step and not worth defaulting to.
     *
     * <p>It is listed last because that is where a picker has to put it, not because it
     * is the top of the scale. Two things follow that the other levels do not carry:
     * it costs the most by some distance, and it is <em>gated</em> — where ultracode is
     * unavailable the CLI rejects the session outright instead of falling back, so the
     * step fails visibly rather than quietly running at the default.
     *
     * <p>It is missing from {@code claude --help}'s {@code (low, medium, high, xhigh,
     * max)} list, which is the whole reason it was absent here: the picker was built
     * from that help text, and the help text is incomplete. The flag itself works — an
     * A/B of the same session with and without it differs by ~6,300 cache-creation
     * tokens, the Workflow tool schema being injected, and {@code --effort ultracode}
     * produces neither the warning nor the fallback an unknown value produces.
     */
    ULTRACODE;

    /** The spelling the {@code --effort} flag takes. */
    public String cliValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The accepted levels, lowest first, for an error message or a picker. */
    public static String allowed() {
        return Stream.of(values()).map(EffortLevel::cliValue).collect(Collectors.joining(", "));
    }

    /**
     * Read a level from what a client sent, blank or null meaning "default".
     *
     * <p>Case-insensitive, and it <em>throws</em> on anything else rather than falling
     * back to the default. That strictness is the point: the CLI itself takes an
     * unknown {@code --effort} value, prints one warning line to stderr and runs at its
     * default anyway. A typo would therefore cost nothing to save, produce no error
     * anywhere the user can see, and leave the page showing "max" over a step that
     * thought at the default. Refusing it here is the only place that reads back.
     *
     * @throws IllegalArgumentException if {@code raw} is not one of {@link #allowed()}
     */
    public static EffortLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String wanted = raw.strip().toLowerCase(Locale.ROOT);
        for (EffortLevel level : values()) {
            if (level.cliValue().equals(wanted)) {
                return level;
            }
        }
        throw new IllegalArgumentException(
                "\"" + raw.strip() + "\" is not an effort level. Use one of " + allowed()
                        + ", or leave it empty for the default.");
    }
}
