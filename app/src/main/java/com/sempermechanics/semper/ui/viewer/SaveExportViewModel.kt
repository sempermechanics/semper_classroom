package com.sempermechanics.semper.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sempermechanics.semper.util.SafCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File

/**
 * The "Save to Files" copy, held across a rotation of [SaveExportActivity].
 *
 * One copy runs per proxy, and whichever instance is current awaits it, so a
 * rotation never starts a second copy into the same document (ADR-016).
 *
 * The copy runs on [io]; the activity builds this through [factory].
 */
internal class SaveExportViewModel(
    app: Application,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(app) {

    private var copy: Deferred<Boolean>? = null

    /** The one copy of [file] into [uri]: started on the first call, shared after. */
    fun copyOnce(uri: Uri, file: File): Deferred<Boolean> = copy ?: start(uri, file).also { copy = it }

    private fun start(uri: Uri, file: File): Deferred<Boolean> {
        val resolver = getApplication<Application>().contentResolver
        return viewModelScope.async(io) { SafCopy.copy(resolver, uri, file) }
    }

    companion object {
        /** Builds the view model with its copy on [io]. */
        fun factory(io: CoroutineDispatcher = Dispatchers.IO): ViewModelProvider.Factory = viewModelFactory {
            initializer { SaveExportViewModel(checkNotNull(this[APPLICATION_KEY]), io) }
        }
    }
}
