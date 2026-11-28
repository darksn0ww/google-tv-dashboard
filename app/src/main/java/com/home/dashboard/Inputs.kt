package com.home.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ConnectedTv
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SettingsInputHdmi
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What is plugged into a socket, guessed from the name you gave it on the TV. The icons are generic
 * ones for each kind of thing, not makers' logos. The first kind whose words appear wins.
 */
enum class InputKind(val icon: ImageVector, words: String) {
    MOTION_CONSOLE(Icons.Outlined.SettingsRemote, "wii( ?u)?"),
    HANDHELD_CONSOLE(Icons.Outlined.VideogameAsset, "nintendo|switch ?2?|game ?boy|steam ?deck"),
    CONSOLE(
        Icons.Outlined.SportsEsports,
        "ps ?[1-5]|psx|play ?station|xbox|sega|mega ?drive|dreamcast|game ?cube|s?nes|n64|atari|retro ?pie|retro|consoles?|games?",
    ),
    CAST(Icons.Outlined.Cast, "chrome ?cast|cast"),
    STREAMER(Icons.Outlined.ConnectedTv, "apple ?tv|fire ?(tv|stick|cube)|roku|shield|google ?tv|android ?tv|now ?tv|streamer"),
    TV_BOX(Icons.Outlined.LiveTv, "sky( ?[a-z]+)?|virgin|tivo|freeview|freesat|youview|bt ?tv|cable|satellite|set ?top ?box|humax"),
    DISC(Icons.Outlined.Album, "blu ?ray|dvd|disc|cd"),
    SOUND(Icons.Outlined.Speaker, "sound ?bar|sonos|speakers?|receiver|avr|amp(lifier)?|audio|stereo"),
    LAPTOP(Icons.Outlined.Laptop, "laptop|mac ?book|chrome ?book"),
    COMPUTER(Icons.Outlined.DesktopWindows, "pc|computer|desktop|mac( ?mini)?|imac|htpc|windows|linux|steam"),
    BOARD(Icons.Outlined.Memory, "raspberry( ?pi)?|pi|kodi|server|nas"),
    CAMERA(Icons.Outlined.Videocam, "camera|cam|camcorder|go ?pro|cctv|nvr|door ?bell"),
    PHONE(Icons.Outlined.Smartphone, "phone|iphone|ipad|tablet"),
    OTHER(Icons.Outlined.SettingsInputHdmi, "(?!)");

    // Whole words only, so "PC" isn't found inside another word. A trailing number is allowed ("PC2").
    val pattern = Regex("(?<![a-z0-9])(?:$words)(?![a-z])")
}

fun inputKind(label: String): InputKind {
    val name = label.lowercase().replace(Regex("[^a-z0-9]+"), " ")
    return InputKind.entries.firstOrNull { it.pattern.containsMatchIn(name) } ?: InputKind.OTHER
}
