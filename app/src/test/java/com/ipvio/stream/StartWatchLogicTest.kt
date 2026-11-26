package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from real TVs: each was a dead end or a too early give-up once. */
class StartWatchLogicTest {
    @Test fun insideManOnResumeAsksTheAddonsInsteadOfGivingUp() {
        // 1.3.280: one-click started the remembered source before the addons answered; it did not start and the list
        // was empty -> "This stream does not start"
        assertEquals(StartWatchLogic.Step.ASK_ADDONS, StartWatchLogic.addonFallback(nextInList = false, askedAgain = false, addonsWithStreams = true, knowsTitle = true))
    }

    @Test fun nextSourceFirstAndNoEndlessAsking() {
        assertEquals(StartWatchLogic.Step.PLAY_NEXT, StartWatchLogic.addonFallback(true, true, true, true))
        assertEquals(StartWatchLogic.Step.GIVE_UP, StartWatchLogic.addonFallback(false, true, true, true))
        assertEquals(StartWatchLogic.Step.GIVE_UP, StartWatchLogic.addonFallback(false, false, false, true))
        assertEquals(StartWatchLogic.Step.GIVE_UP, StartWatchLogic.addonFallback(false, false, true, false))
    }

    @Test fun aSourceThatIsDownloadingIsNotDroppedAfterSixSeconds() {
        // DragonStreams on Resume: the server cannot seek, so the player reads up to the spot (bytes arriving)
        assertTrue(StartWatchLogic.giveUpMs(addonTitle = true, receiving = true) >= 30_000L)
        assertEquals(6_000L, StartWatchLogic.giveUpMs(addonTitle = true, receiving = false))
        assertEquals(20_000L, StartWatchLogic.giveUpMs(addonTitle = false, receiving = false))
    }
}
