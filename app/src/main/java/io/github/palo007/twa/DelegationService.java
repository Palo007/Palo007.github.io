package io.github.palo007.twa;

import android.app.Notification;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import io.github.palo007.twa.quicklog.DropboxAuth;
import io.github.palo007.twa.quicklog.ReminderDedupe;
import io.github.palo007.twa.quicklog.ReminderItem;
import io.github.palo007.twa.quicklog.ReminderListKt;
import io.github.palo007.twa.quicklog.ReminderSyncWorker;

public class DelegationService extends
        com.google.androidbrowserhelper.trusted.DelegationService {

    private static final String TAG = "QuestaReminder";

    @Override
    public void onCreate() {
        super.onCreate();

        // HAND-OWNED (quick-log): faster refresh whenever the delegated-notification service is
        // (re)started, not only on app launch or the periodic worker's own schedule.
        try {
            if (DropboxAuth.isConnected(this)) {
                ReminderSyncWorker.enqueueNow(this);
            }
        } catch (Throwable t) {
            // Never let this break notification delegation.
        }
    }

    /**
     * HAND-OWNED (quick-log). Signature verified against androidx.browser:browser 1.9.0-alpha04
     * (androidx.browser.trusted.TrustedWebActivityService, the androidbrowserhelper
     * DelegationService's parent):
     * `public boolean onNotifyNotificationWithChannel(String, int, Notification, String)`.
     *
     * Phase 1E: SLOT dedupe instead of a title+window guess. The web app now fires both on-time
     * reminders (title = task title) and missed ones (body starts with `Missed at HH:MM - `). A
     * missed notification names its own slot time directly; an on-time one does not, so both
     * "now" and "now-1min" are tried as candidate slots to absorb the gap between the native
     * alarm firing and this delegated call arriving. If ANY candidate slot was already shown
     * natively, the web copy is dropped; otherwise every candidate is marked shown-by-web so
     * ReminderReceiver can symmetrically skip its own copy of that exact slot. Only titles that
     * appear in the stored reminder list are considered at all, so ad-hoc web notifications
     * (e.g. the "Questa Test" button) are never dropped or recorded.
     */
    @Override
    public boolean onNotifyNotificationWithChannel(
            String platformTag, int platformId, Notification notification, String channelName) {
        try {
            CharSequence titleCs = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            String title = titleCs != null ? titleCs.toString() : null;
            if (title != null) {
                CharSequence bodyCs = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                String body = bodyCs != null ? bodyCs.toString() : null;
                // HAND-OWNED (quick-log) P7: decision extracted to the pure ReminderDedupe.webDecision
                // (JVM-tested for web/native key parity); behaviour unchanged.
                ReminderDedupe.WebDecision d = ReminderDedupe.webDecision(
                        title, body, System.currentTimeMillis(), TimeZone.getDefault(),
                        knownReminderTitles(), slot -> ReminderDedupe.nativeShown(this, slot));
                if (d instanceof ReminderDedupe.WebDecision.Drop) {
                    String slot = ((ReminderDedupe.WebDecision.Drop) d).getSlot();
                    Log.i(TAG, "dropped title=\"" + title + "\" slot=" + slot);
                    return true; // Drop: native already showed this slot.
                }
                if (d instanceof ReminderDedupe.WebDecision.Pass) {
                    List<String> slots = ((ReminderDedupe.WebDecision.Pass) d).getSlots();
                    for (String slot : slots) {
                        ReminderDedupe.markWeb(this, slot);
                    }
                    Log.i(TAG, "passed title=\"" + title + "\" slots=" + slots);
                }
            }
        } catch (Throwable t) {
            // Never let dedupe bookkeeping break the web notification path.
        }
        return super.onNotifyNotificationWithChannel(platformTag, platformId, notification, channelName);
    }

    /** HAND-OWNED (quick-log): titles in the stored reminder list; empty if none or unparsable. */
    private Set<String> knownReminderTitles() {
        Set<String> out = new HashSet<>();
        String text = DropboxAuth.prefs(this).getString(ReminderSyncWorker.KEY_REMINDERS_JSON, null);
        if (text == null) return out;
        List<ReminderItem> items = ReminderListKt.parseReminderList(text);
        if (items == null) return out;
        for (ReminderItem item : items) {
            out.add(item.getTitle());
        }
        return out;
    }
}

