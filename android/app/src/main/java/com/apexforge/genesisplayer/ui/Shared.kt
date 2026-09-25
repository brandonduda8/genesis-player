package com.apexforge.genesisplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** Ember-gradient placeholder shown when a track has no artwork. */
@Composable
fun EmberPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(listOf(EmberOrange.copy(alpha = 0.55f), PhoenixGold.copy(alpha = 0.25f), CardDark))
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.MusicNote, "No artwork", tint = AshBlack, modifier = androidx.compose.ui.Modifier.size(48.dp))
    }
}
