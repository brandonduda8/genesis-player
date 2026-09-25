package com.apexforge.genesisplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.apexforge.genesisplayer.data.ApolloNet
import com.apexforge.genesisplayer.data.ApolloStore
import com.apexforge.genesisplayer.data.EmberArt

/**
 * Track artwork (VISION.md §5): renders `artwork_url` from the catalog;
 * the fallback is the deterministic seeded procedural ember renderer keyed
 * by hash(track id) — ember→gold flame geometry that reads as intentional
 * craft, NEVER a placeholder look.
 *
 * Respects the Wi-Fi-only artwork download setting: when enabled and the
 * device is off Wi-Fi, remote art is not even requested — the ember render
 * shows instead.
 */
@Composable
fun TrackArtwork(
    trackId: String,
    artworkUrl: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = "Artwork",
    corner: Dp = 8.dp
) {
    val ctx = LocalContext.current
    val wifiOnly = remember { ApolloStore.wifiOnlyArtwork(ctx) }
    val onWifi = remember { ApolloNet.isOnWifi(ctx) }
    val fetchRemote = artworkUrl.isNotEmpty() && (!wifiOnly || onWifi)
    if (fetchRemote) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = contentDescription,
            modifier = modifier.clip(RoundedCornerShape(corner)),
            contentScale = ContentScale.Crop
        )
    } else {
        val bmp = remember(trackId) { EmberArt.renderBitmap(trackId, 256) }
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.clip(RoundedCornerShape(corner)),
            contentScale = ContentScale.Crop
        )
    }
}
