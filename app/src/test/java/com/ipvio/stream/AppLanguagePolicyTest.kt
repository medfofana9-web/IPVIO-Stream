package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguagePolicyTest {
    @Test fun newInstallationUsesEnglish() {
        assertEquals("en", AppLanguagePolicy.initial(null, false))
    }

    @Test fun existingAutomaticInstallationMigratesToEnglish() {
        assertEquals("en", AppLanguagePolicy.initial("", false))
    }

    @Test fun explicitlySelectedLanguageSurvivesMigration() {
        assertEquals("de", AppLanguagePolicy.initial("de", false))
        assertEquals("es", AppLanguagePolicy.initial("es", false))
        assertEquals("en", AppLanguagePolicy.initial("en", false))
    }

    @Test fun viewerCanChooseAutomaticAgainAfterMigration() {
        assertEquals("", AppLanguagePolicy.initial("", true))
        assertEquals("de", AppLanguagePolicy.initial("de", true))
        assertEquals("en", AppLanguagePolicy.initial(null, true))
    }
}
