package app.morphe.extension.newx.timeline;

/**
 * JP jp24.13.20: holds the currently displayed account's user ID for
 * the extension. The host timeline component
 * ({@code Lcom/x/urt/c0;}) notifies this holder through the synthetic
 * bridge {@code piko$newxNotifyViewer} added by the patch (invoked
 * from the component's constructor right after its UserIdentifier
 * field is stored, and from the existing {@code piko$newxAccountId}
 * bridge body as a self-healing second path), so the value tracks
 * account switches: constructing a component tree for another
 * account overwrites it, and a component without a user (logged
 * out / placeholder) clears it to 0.
 *
 * <p>The ID space is the host's user ID (rest_id) — the same space
 * as the harvested followed-author IDs (both come from the
 * UserIdentifier's numeric field), so the string form is used
 * directly as the recommend-keep store's partition key.
 *
 * <p>0 means "viewer unknown": while unknown, the recommend-keep
 * gate is fully off and nothing is harvested (PERACCOUNT_DESIGN
 * §1.4 / §3 / §4). There is deliberately no fallback to any shared
 * state.
 */
public final class CurrentViewer {
    private static volatile long accountId;

    private CurrentViewer() {
    }

    /** Called from the host component bridge; 0 clears to unknown. */
    public static void notifyAccount(long id) {
        accountId = id;
    }

    public static long accountId() {
        return accountId;
    }

    /** Partition key, or null when the viewer is unknown. */
    public static String accountKey() {
        long id = accountId;
        return id > 0 ? String.valueOf(id) : null;
    }
}
