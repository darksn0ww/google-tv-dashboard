package com.home.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class FavouritesTest {
    @Test fun editsLastUntilTheConfigListChanges() {
        val saved = Favourites.encode(listOf("a", "b"), listOf("b", "c"))
        assertEquals(listOf("b", "c"), Favourites.decode(saved, listOf("a", "b")))
        assertEquals(listOf("d"), Favourites.decode(saved, listOf("d")))
        assertEquals(listOf("a"), Favourites.decode(null, listOf("a")))
        assertEquals(listOf("a"), Favourites.decode("rubbish", listOf("a")))
    }

    @Test fun movesPastAppsThatAreNotInstalled() {
        val list = listOf("a", "missing", "b")
        assertEquals(listOf("b", "missing", "a"), Favourites.move(list, listOf("a", "b"), "a", 1))
        assertEquals(list, Favourites.move(list, listOf("a", "b"), "a", -1))
        assertEquals(list, Favourites.move(list, listOf("a", "b"), "b", 1))
        assertEquals(list, Favourites.move(list, listOf("a", "b"), "nope", 1))
    }
}
