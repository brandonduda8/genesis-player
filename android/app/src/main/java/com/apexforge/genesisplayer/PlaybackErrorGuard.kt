package com.apexforge.genesisplayer

/**
 * Pure decision logic for player errors (AUDIT §2.1 defect 1).
 *
 * A dead/expired URL must never stall the player silently: the service skips
 * to the next playable track and shows a user-visible message. But a whole
 * queue of dead URLs must not spin forever either — after [maxConsecutive]
 * straight errors the service stops and says so honestly. A single success
 * resets the counter.
 *
 * Pure (no Android calls) so the API-34 instrumented tests pin it.
 */
class PlaybackErrorGuard(private val maxConsecutive: Int = 5) {

    sealed class Decision {
        /** Skip to the next playable track, telling the user why. */
        data class Skip(val message: String) : Decision()

        /** Nothing playable — stop and say so honestly. */
        data class Stop(val message: String) : Decision()
    }

    var consecutiveErrors = 0
        private set

    fun onError(): Decision {
        consecutiveErrors++
        return if (consecutiveErrors >= maxConsecutive) {
            Decision.Stop("Nothing in this queue would play — stopped. Pick another lane.")
        } else {
            Decision.Skip("That track wouldn't play — skipped ahead.")
        }
    }

    fun onSuccess() {
        consecutiveErrors = 0
    }
}
