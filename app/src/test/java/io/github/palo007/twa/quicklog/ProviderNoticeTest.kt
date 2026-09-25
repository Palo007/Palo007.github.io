// HAND-OWNED (quick-log). JVM tests for the one-time "no TWA provider" notice (TWA-03).
package io.github.palo007.twa.quicklog

import com.google.androidbrowserhelper.trusted.TwaProviderPicker.LaunchMode
import io.github.palo007.twa.quicklog.ProviderNotice.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderNoticeTest {
    @Test fun showsOnceWhenProviderIsNotTwa() {
        assertEquals(Decision.SHOW, ProviderNotice.decide(LaunchMode.CUSTOM_TAB, false))
        assertEquals(Decision.SHOW, ProviderNotice.decide(LaunchMode.BROWSER, false))
    }

    @Test fun notAgainAfterShown() {
        assertEquals(Decision.NONE, ProviderNotice.decide(LaunchMode.CUSTOM_TAB, true))
        assertEquals(Decision.NONE, ProviderNotice.decide(LaunchMode.BROWSER, true))
    }

    @Test fun neverShownWhenChromeTwaAvailable() {
        assertEquals(Decision.NONE, ProviderNotice.decide(LaunchMode.TRUSTED_WEB_ACTIVITY, false))
    }

    @Test fun resetsWhenTwaIsBack() {
        assertEquals(Decision.RESET, ProviderNotice.decide(LaunchMode.TRUSTED_WEB_ACTIVITY, true))
    }
}
