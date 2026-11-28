package com.ipvio.stream

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRecoveryLogicTest {
    @Test fun codesMatchMedia3() {
        assertTrue(PlayerRecoveryLogic.isNetwork(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, 0))
        assertTrue(PlayerRecoveryLogic.isNetwork(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, 0))
        assertTrue(PlayerRecoveryLogic.isNetwork(PlaybackException.ERROR_CODE_TIMEOUT, 0))
        assertTrue(PlayerRecoveryLogic.isNetwork(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, 0))
    }

    @Test fun sourceErrorsAreNotNetwork() {
        // The server answered (expired link, not found): that is the source, not the internet
        assertFalse(PlayerRecoveryLogic.isNetwork(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertFalse(PlayerRecoveryLogic.isNetwork(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, 503))
        assertFalse(PlayerRecoveryLogic.isNetwork(PlaybackException.ERROR_CODE_DECODING_FAILED, 0))
    }

    @Test fun backoffIsBounded() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), (1..6).map { PlayerRecoveryLogic.backoffMs(it) })
    }

    @Test fun aMovieSourceThatKeepsFailingWhileOnlineIsCheckedAfterTwoTries() {
        // #82: a 4K provider file stopped mid-show; the TV was online and showed "reconnecting" for minutes
        assertFalse(PlayerRecoveryLogic.askInternet(1, online = true, live = false))
        assertFalse(PlayerRecoveryLogic.askInternet(2, online = true, live = false))
        assertTrue(PlayerRecoveryLogic.askInternet(3, online = true, live = false))
        // Offline: wait for the internet as before; live channels keep their own reconnect
        assertFalse(PlayerRecoveryLogic.askInternet(5, online = false, live = false))
        assertFalse(PlayerRecoveryLogic.askInternet(5, online = true, live = true))
    }
}
