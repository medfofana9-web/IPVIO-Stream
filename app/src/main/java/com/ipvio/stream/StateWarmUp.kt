package com.ipvio.stream

/**
 * Creates the Compose states of app-wide objects on the main thread at start, before background work (account sync,
 * frame metrics, freeze watch, source checks) can touch an object first. A state created on a background thread after a
 * screen's snapshot crashes that screen ("Reading a state that was created after the snapshot was taken", auto-report
 * #63). tools/check_compose_state.py (pr-quality.yml) fails when a new object with state is not listed here.
 */
object StateWarmUp {
    fun run() {
        runCatching { ProfileAvatars.version + AccountCloud.pins.size }
        runCatching { Ambient.on; Lite.on; Look.motion; FofPrefs.cleanLook }
        runCatching { IntroLearn.version }
    }
}
