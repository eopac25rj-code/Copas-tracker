package com.copastracker.app

import android.content.Context

class CardStorage(context: Context) {

    private val prefs = context.getSharedPreferences("copas_tracker_prefs", Context.MODE_PRIVATE)

    fun savePlayedCards(playedCards: Set<String>) {
        prefs.edit().putStringSet(KEY_PLAYED_CARDS, playedCards.toSet()).apply()
    }

    fun loadPlayedCards(): MutableSet<String> {
        return prefs.getStringSet(KEY_PLAYED_CARDS, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    fun clearPlayedCards() {
        prefs.edit().remove(KEY_PLAYED_CARDS).apply()
    }

    companion object {
        private const val KEY_PLAYED_CARDS = "played_cards"
        val SUITS = listOf("♠", "♥", "♦", "♣")
        val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

        fun allCards(): List<String> {
            val deck = mutableListOf<String>()
            for (suit in SUITS) {
                for (rank in RANKS) {
                    deck.add("$rank$suit")
                }
            }
            return deck
        }
    }
}
