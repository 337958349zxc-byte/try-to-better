package com.trytobatter.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trytobatter.app.core.data.AppRepository
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiMessage(val text: String, val undoable: Boolean = false)

class BatterViewModel(application: Application) : AndroidViewModel(application) {
    val repository: AppRepository = (application as BatterApplication).repository
    val state = repository.state
    val updates = repository.updates
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError = _loadError.asStateFlow()
    init { load() }

    fun load() { viewModelScope.launch {
        _loadError.value = null
        try { repository.load() } catch(e: Exception) { _loadError.value = "本地数据读取失败：${e.message}" }
    } }

    fun perform(message: String = "", undoable: Boolean = false, onDone: () -> Unit = {}, action: suspend AppRepository.() -> Unit) {
        viewModelScope.launch {
            try {
                repository.action()
                if(message.isNotBlank()) _messages.emit(UiMessage(message, undoable))
                onDone()
            } catch(e: Exception) { _messages.emit(UiMessage(e.message ?: "操作未完成，请重试")) }
        }
    }
    fun notify(text: String) { _messages.tryEmit(UiMessage(text)) }
}
