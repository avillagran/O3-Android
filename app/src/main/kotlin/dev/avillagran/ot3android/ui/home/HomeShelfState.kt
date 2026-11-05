package dev.avillagran.ot3android.ui.home

import dev.avillagran.ot3android.core.home.HomeItem
import dev.avillagran.ot3android.core.home.HomeUiState
import dev.avillagran.ot3android.core.home.ShelfState

/** Aplica los pliegues locales sin esperar otra lectura del dominio o de la conexión. */
internal fun HomeUiState.withShelfExpansion(shelves: ShelfState): HomeUiState {
    var visible = true
    val folded = buildList {
        items.forEach { item ->
            when (item) {
                is HomeItem.ShelfHeader -> {
                    visible = shelves.expanded(item.shelf)
                    add(item.copy(expanded = visible))
                }
                is HomeItem.MessagesHeader -> { visible = true; add(item) }
                else -> if (visible) add(item)
            }
        }
    }
    return copy(shelves = shelves, items = folded)
}
