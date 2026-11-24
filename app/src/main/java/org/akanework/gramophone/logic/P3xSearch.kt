package org.akanework.gramophone.logic

import kotlinx.coroutines.flow.MutableStateFlow

object P3xSearch {
    val query = MutableStateFlow("")
    var openBar: (() -> Unit)? = null
}
