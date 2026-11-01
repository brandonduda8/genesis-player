package com.apexforge.genesisplayer.data.mymusic.crate

/**
 * Where one of the owner's songs can be heard, in one honest word. Drives every badge and every offline decision.
 *  OFFLINE       a file on this phone (imported, saved under permission, or already on the device): plays with no internet
 *  DOWNLOADABLE  the exact recording, and its rights holder allows saving it: one tap to make it OFFLINE
 *  ONLINE_EXACT  the exact recording streams in Aurum (EQ, background), but needs the internet
 *  ALTERNATE     only another version exists (slowed, remix, live and so on); never played in this song's place
 *  YOUTUBE       no legitimate copy found: official YouTube playback only, needs the internet
 *  NOT_CHECKED   the Crate hasn't checked this artist yet
 */
enum class SongStatus(val label: String) {
    OFFLINE("OFFLINE"),
    DOWNLOADABLE("CAN SAVE"),
    ONLINE_EXACT("ONLINE · EXACT"),
    ALTERNATE("ALT VERSION"),
    YOUTUBE("YOUTUBE"),
    NOT_CHECKED("NOT CHECKED");

    val worksOffline get() = this == OFFLINE
    val playsInAurum get() = this == OFFLINE || this == DOWNLOADABLE || this == ONLINE_EXACT

    companion object {
        fun of(hasOfflineFile: Boolean, match: CrateMatch?): SongStatus = when {
            hasOfflineFile -> OFFLINE
            match == null -> NOT_CHECKED
            match.kind == MatchKind.EXACT && match.downloadable -> DOWNLOADABLE
            match.kind == MatchKind.EXACT -> ONLINE_EXACT
            match.kind == MatchKind.ALTERNATE -> ALTERNATE
            else -> YOUTUBE
        }
    }
}
