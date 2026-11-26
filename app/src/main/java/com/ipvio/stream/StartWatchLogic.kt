package com.ipvio.stream

/**
 * When the player gives up on a movie/episode source that shows no picture, and what it does next (tested in
 * StartWatchLogicTest, which holds the cases that went wrong on real TVs).
 */
object StartWatchLogic {
    /**
     * Wait before the next source is tried: an addon title (more sources to try) that receives nothing is left after 6 s;
     * a source that is still receiving data (e.g. resuming far into a file on a server that cannot seek) gets 30 s;
     * anything else 20 s.
     */
    fun giveUpMs(addonTitle: Boolean, receiving: Boolean): Long = when {
        receiving -> 30_000L
        addonTitle -> 6_000L
        else -> 20_000L
    }

    enum class Step { PLAY_NEXT, ASK_ADDONS, GIVE_UP }

    /**
     * Addon title whose source failed: the next source of the list, else (once) ask the addons again – the list is taken
     * when the source is picked, often before every addon answered – else give up.
     */
    fun addonFallback(nextInList: Boolean, askedAgain: Boolean, addonsWithStreams: Boolean, knowsTitle: Boolean): Step = when {
        nextInList -> Step.PLAY_NEXT
        !askedAgain && addonsWithStreams && knowsTitle -> Step.ASK_ADDONS
        else -> Step.GIVE_UP
    }
}
