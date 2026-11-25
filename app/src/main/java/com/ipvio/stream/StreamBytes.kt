package com.ipvio.stream

/**
 * Bytes the player has received from the network (all streams). A sports feed that has received nothing since it was
 * opened is dead or hanging; one that receives data is only slow to show its first picture and keeps its full time.
 */
object StreamBytes {
    val total = java.util.concurrent.atomic.AtomicLong(0L)
}
