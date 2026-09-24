package io.github.palo007.twa;

import android.app.Notification;
import android.util.Log;

import java.util.List;
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
            if (title != null && isKnownReminderTitle(title)) {
                CharSequence bodyCs = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                String body = bodyCs != null ? bodyCs.toString() : null;
                long now = System.currentTimeMillis();
                TimeZone tz = TimeZone.getDefault();
                String today = ReminderDedupe.localDateString(now, tz);
                List<String> times = ReminderDedupe.candidateTimes(body, now, tz);

                for (String time : times) {
                    String slot = ReminderDedupe.buildSlotKey(title, today, time);
                    if (ReminderDedupe.nativeShown(this, slot)) {
                        Log.i(TAG, "dropped title=\"" + title + "\" slot=" + slot);
                        return true; // Drop: native already showed this slot.
                    }
                }
                for (String time : times) {
                    ReminderDedupe.markWeb(this, ReminderDedupe.buildSlotKey(title, today, time));
                }
                Log.i(TAG, "passed title=\"" + title + "\" slots=" + times);
            }
        } catch (Throwable t) {
            // Never let dedupe bookkeeping break the web notification path.
        }
        return super.onNotifyNotificationWithChannel(platformTag, platformId, notification, channelName);
    }

    private boolean isKnownReminderTitle(String title) {
        String text = DropboxAuth.prefs(this).getString(ReminderSyncWorker.KEY_REMINDERS_JSON, null);
        if (text == null) return false;
        List<ReminderItem> items = ReminderListKt.parseReminderList(text);
        if (items == null) return false;
        for (ReminderItem item : items) {
            if (title.equals(item.getTitle())) return true;
        }
        return false;
    }
}

