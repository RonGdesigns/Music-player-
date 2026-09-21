package com.irondigital.spindle.ui.personal

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irondigital.spindle.data.personal.*
import com.irondigital.spindle.spindle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PersonalViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.spindle
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val data = app.listening.data.catch { message.value = "Could not read saved listening data. Your files are unchanged."; emit(ListeningData()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningData())
    val favorites = app.favoriteIds
    val stats = app.playStats
    fun runAction(success: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try { action(); message.value = success }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                message.value = error.message ?: "Could not save that change"
            } finally { busy.value = false }
        }
    }
    fun pin(pin: PinnedCollection) = runAction("Pins updated") {
        app.listening.update { d -> d.copy(pins = if (d.pins.any { it.id == pin.id }) d.pins.filterNot { it.id == pin.id } else d.pins + pin) }
    }
    fun removeSession(id: String) = runAction("Session removed") { app.listening.update { d ->
        d.copy(sessions = d.sessions.filterNot { it.id == id }, activeSessionId = d.activeSessionId?.takeUnless { it == id }) } }
    fun renameSession(id: String, name: String) = runAction("Session renamed") { app.listening.update { d ->
        require(name.trim().isNotEmpty()); d.copy(sessions = d.sessions.map { if (it.id == id) it.copy(name = name.trim().take(80)) else it }) } }
    fun saveMix(rule: MixRule) = runAction("Smart playlist saved") { app.listening.update { d ->
        require(rule.name.isNotBlank()); d.copy(mixes = d.mixes.filterNot { it.id == rule.id } + rule.copy(name = rule.name.trim().take(80))) } }
    fun removeMix(id: String) = runAction("Smart playlist removed") { app.listening.update { d -> d.copy(mixes = d.mixes.filterNot { it.id == id }) } }
    fun addBookmark(bookmark: TrackBookmark) = runAction("Bookmark saved") { app.listening.update { d -> d.copy(bookmarks = d.bookmarks + bookmark) } }
    fun removeBookmark(id: String) = runAction("Bookmark removed") { app.listening.update { d -> d.copy(bookmarks = d.bookmarks.filterNot { it.id == id }) } }
    fun widget(appearance: WidgetAppearance) = runAction("Widget appearance applied") {
        app.listening.update { it.copy(widget = appearance) }
        com.irondigital.spindle.widget.NowPlayingWidget.refresh(app)
    }
    fun artwork(key: String, uri: Uri) = runAction("Cover art saved") { app.customArtwork.set(key, uri) }
    fun resetArtwork(key: String) = runAction("Original artwork restored") { app.customArtwork.clear(key) }
}
