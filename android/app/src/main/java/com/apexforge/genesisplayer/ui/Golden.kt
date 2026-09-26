package com.apexforge.genesisplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.data.Energy
import com.apexforge.genesisplayer.data.Library
import kotlinx.coroutines.delay

/**
 * Golden Player Phase 1 design tokens — the web player's LOCKED palette
 * (ts-spaces/music-player theme.css). The neutrals are fixed; ember and gold
 * still read the live RemoteTheme so a remote accent change keeps working.
 */
object Golden {
    val bg = Color(0xFF070708)
    val surface = Color(0xFF111114)
    val surface2 = Color(0xFF19191E)
    val text = Color(0xFFF7F4ED)
    val dim = Color(0xFFA29D93)
    val border = Color(0xFF2C2B31)
    val deck = Color(0xFF0A0A0C)
    val purple = Color(0xFF8B2CFF)
    val ember: Color get() = RemoteTheme.palette.value.accent
    val gold: Color get() = RemoteTheme.palette.value.gold

    /** Condensed 900 display face (web: "Avenir Next Condensed" 900). */
    val display = FontFamily(
        android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
    )

    fun displayStyle(size: TextUnit) = TextStyle(
        fontFamily = display, fontWeight = FontWeight.Black,
        fontSize = size, lineHeight = size * 0.95f, letterSpacing = (-0.04).em
    )
}

/** Uppercase micro-label ("APOLLO / FOR YOU", "YOUR CRATE"). */
@Composable
fun Kicker(text: String, color: Color = Golden.dim, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.14.em, modifier = modifier
    )
}

/** Kicker + condensed heading + optional trailing action (web .section-heading). */
@Composable
fun SectionHeading(
    kicker: String,
    title: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 30.sp,
    trailing: @Composable () -> Unit = {}
) {
    Row(modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Kicker(kicker)
            Spacer(Modifier.height(3.dp))
            Text(title, color = Golden.text, style = Golden.displayStyle(size))
        }
        trailing()
    }
}

/** The Apollo orb: gold core -> ember -> deep red, with an ember glow. */
@Composable
fun ApolloOrb(size: Dp = 25.dp) {
    Box(
        Modifier.size(size).drawBehind {
            val r = this.size.minDimension / 2f
            drawCircle(
                Brush.radialGradient(
                    listOf(Golden.ember.copy(alpha = 0.45f), Color.Transparent),
                    center = center, radius = r * 1.6f
                ),
                radius = r * 1.6f
            )
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFFFF3B5), 0.07f to Color(0xFFFFF3B5),
                    0.11f to Golden.gold, 0.52f to Golden.ember, 1f to Color(0xFF6B1000),
                    center = Offset(this.size.width * 0.38f, this.size.height * 0.34f),
                    radius = r * 1.25f
                ),
                radius = r
            )
        }
    )
}

/** NATIVE (Audius, ember) / SOUNDCLOUD (stream-only, muted) source badge. */
@Composable
fun SourceBadge(native: Boolean) {
    val label = if (native) "NATIVE" else "SOUNDCLOUD"
    Box(
        Modifier.clip(RoundedCornerShape(3.dp))
            .background(if (native) Golden.ember else Golden.surface2)
            .then(if (native) Modifier else Modifier.border(1.dp, Golden.border, RoundedCornerShape(3.dp)))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            label, color = if (native) Color.White else Golden.dim, fontSize = 8.sp,
            lineHeight = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.08.em
        )
    }
}

/** Source badge for any id the player may hold (catalog, For You, snapshot). */
@Composable
fun SourceBadgeFor(trackId: String?) {
    val id = trackId ?: return
    val t = Library.track(id)
    when {
        t != null -> SourceBadge(native = t.soundcloudUrl.isEmpty())
        id.startsWith("fy_") -> SourceBadge(native = true)
    }
}

/** Filter pill (web .filter-row button). Selected = cream fill. */
@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (selected) Golden.text else Color.Transparent)
            .border(1.dp, if (selected) Golden.text else Golden.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label, color = if (selected) Golden.bg else Golden.dim,
            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
        )
    }
}

/** Ember primary button (web .add-primary). */
@Composable
fun EmberButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(5.dp))
            .background(Golden.ember).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

/** Ember text button (web .text-button). */
@Composable
fun EmberTextButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = Golden.ember.copy(alpha = if (enabled) 1f else 0.5f),
        fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(8.dp)
    )
}

@Composable
fun GoldenDivider() = HorizontalDivider(color = Golden.border, thickness = 1.dp)

/**
 * Live player state shared by every Phase-1 surface (play-disc highlight,
 * mini-player, queue count). One 500 ms poll instead of one per screen.
 */
@Stable
class PlayerPulse {
    var currentId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var artwork by mutableStateOf("")
    var upcoming by mutableIntStateOf(0)
}

@Composable
fun rememberPlayerPulse(controller: MediaController?): PlayerPulse {
    val pulse = remember { PlayerPulse() }
    LaunchedEffect(controller) {
        while (true) {
            val c = controller
            if (c != null) {
                val item = c.currentMediaItem
                pulse.currentId = item?.mediaId
                pulse.isPlaying = c.isPlaying
                if (item != null) {
                    val md = c.mediaMetadata
                    pulse.title = (md.title ?: "").toString()
                    pulse.artist = (md.artist ?: "").toString()
                    pulse.artwork = md.artworkUri?.toString() ?: ""
                }
                pulse.upcoming = if (item == null) 0
                else (c.mediaItemCount - c.currentMediaItemIndex - 1).coerceAtLeast(0)
            }
            delay(500)
        }
    }
    return pulse
}

/**
 * The web crate row: play-disc (ember when playing), title/artist ellipsis,
 * source badge, vertical energy dot, heart, and + (play next — the native
 * equivalent of the web's add-to-playlist, since user playlists are Phase 2).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    title: String,
    artist: String,
    native: Boolean?,
    energy: Energy?,
    playing: Boolean,
    loved: Boolean?,
    onPlay: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onLove: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(if (playing) Golden.ember else Golden.surface)
                .border(1.dp, if (playing) Golden.ember else Golden.border, CircleShape)
                .clickable(onClick = onPlay)
                .semantics { contentDescription = if (playing) "Playing $title" else "Play $title" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                tint = if (playing) Color.White else Golden.text, modifier = Modifier.size(18.dp)
            )
        }
        Column(
            Modifier.weight(1f).combinedClickable(onClick = onPlay, onLongClick = onLongPress)
                .padding(vertical = 9.dp)
        ) {
            Text(
                title, color = Golden.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                artist, color = Golden.dim, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (native != null) {
                Spacer(Modifier.height(4.dp))
                SourceBadge(native)
            }
        }
        if (energy != null) {
            Box(
                Modifier.width(6.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(
                    when (energy) {
                        Energy.BANGER -> Golden.ember
                        Energy.SOFT -> Golden.gold
                        Energy.MID -> Golden.dim
                    }
                )
            )
        }
        if (onLove != null && loved != null) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onLove)
                    .semantics { contentDescription = if (loved) "Unlove $title" else "Love $title" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (loved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null,
                    tint = if (loved) Golden.ember else Golden.dim, modifier = Modifier.size(18.dp)
                )
            }
        }
        if (onPlayNext != null) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onPlayNext)
                    .semantics { contentDescription = "Play $title next" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, null, tint = Golden.dim, modifier = Modifier.size(20.dp))
            }
        }
    }
    GoldenDivider()
}
