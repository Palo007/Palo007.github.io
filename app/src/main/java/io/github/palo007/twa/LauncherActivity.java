/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.palo007.twa;

import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import io.github.palo007.twa.quicklog.DropboxAuth;
import io.github.palo007.twa.quicklog.LaunchSync;
import io.github.palo007.twa.quicklog.ProviderNotice;
import io.github.palo007.twa.quicklog.ReminderScheduler;
import io.github.palo007.twa.quicklog.ReminderSyncWorker;

public class LauncherActivity
        extends com.google.androidbrowserhelper.trusted.LauncherActivity {
    

    

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Setting an orientation crashes the app due to the transparent background on Android 8.0
        // Oreo and below. We only set the orientation on Oreo and above. This only affects the
        // splash screen and Chrome will still respect the orientation.
        // See https://github.com/GoogleChromeLabs/bubblewrap/issues/496 for details.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }

        // HAND-OWNED (quick-log): cheap refresh every time the app is opened, not only on
        // process start (QuickLogBootstrap only runs once per process).
        try {
            if (DropboxAuth.isConnected(this)) {
                ReminderSyncWorker.enqueueNow(this);
                // HAND-OWNED (quick-log, TWA-26): delayed (~3 min) unique follow-up sync so a reminder
                // edited early in the TWA session is armed without waiting for the periodic run.
                // Not expedited (TWA-27).
                LaunchSync.enqueueFollowUp(this);
            }
        } catch (Throwable t) {
            // Never let this break the TWA launch.
        }

        // HAND-OWNED (quick-log, TWA-03): one-time Toast when no TWA provider (Chrome) is available and
        // the launch falls back to a Custom Tab in another browser with separate storage.
        try {
            ProviderNotice.maybeShow(this);
        } catch (Throwable t) {
            // Never let this break the TWA launch.
        }
    }

    @Override
    protected Uri getLaunchingUrl() {
        // Get the original launch Url.
        Uri uri = super.getLaunchingUrl();

        // HAND-OWNED (quick-log): tell the web app it may go silent on missed reminders when
        // native alarms are ready to cover them. Any failure here must fall back to the plain
        // uri so the launch itself is never at risk.
        try {
            boolean ready = ReminderScheduler.nativeReady(this);
            Log.i("QuestaReminder", "nativeReady=" + ready);
            if (ready) {
                uri = uri.buildUpon().appendQueryParameter("nr", "1").build();
            }
        } catch (Throwable t) {
            // Fall through: uri is still the plain, unmodified one.
        }

        return uri;
    }
}
