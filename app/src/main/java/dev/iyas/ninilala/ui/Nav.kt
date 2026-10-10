package dev.iyas.ninilala.ui

import dev.iyas.ninilala.watch.DiscoveredStream

sealed interface Destination {
    data object Home : Destination
    data object Stream : Destination
    data object Watch : Destination
    data class Playing(val stream: DiscoveredStream) : Destination
}
