package com.home.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class InputsTest {
    private fun check(kind: InputKind, vararg names: String) =
        names.forEach { assertEquals(it, kind, inputKind(it)) }

    @Test fun guessesFromTheName() {
        check(InputKind.MOTION_CONSOLE, "Wii", "wii u", "Nintendo Wii")
        check(InputKind.HANDHELD_CONSOLE, "Switch", "Nintendo Switch 2", "Steam Deck")
        check(InputKind.CONSOLE, "PS5", "PlayStation 4", "Xbox Series X", "SNES", "Games")
        check(InputKind.CAST, "Chromecast")
        check(InputKind.STREAMER, "Apple TV", "Fire TV Stick", "Google TV Streamer", "Roku")
        check(InputKind.TV_BOX, "Sky", "Sky Q", "Virgin Media", "Freeview box")
        check(InputKind.DISC, "Blu-ray", "DVD player")
        check(InputKind.SOUND, "Soundbar", "AV Receiver", "Sonos")
        check(InputKind.LAPTOP, "MacBook", "Work laptop")
        check(InputKind.COMPUTER, "PC", "Gaming PC2", "Mac mini")
        check(InputKind.BOARD, "Raspberry Pi", "Kodi")
        check(InputKind.CAMERA, "GoPro", "Doorbell")
        check(InputKind.PHONE, "iPhone")
    }

    @Test fun leavesPlainAndLookalikeNamesAlone() {
        check(InputKind.OTHER, "HDMI 1", "AV", "Component", "Upstairs", "Spare", "Pcm", "Campsite", "")
    }
}
