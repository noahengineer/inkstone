package dev.inkstone.app.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageTemplate
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * A shell around [NoteEditor], supplying it with `viewModelScope`.
 *
 * There is deliberately no logic here. It used to be the other way round — the editor *was* the
 * view model — and that is how the binding bug in [NoteEditor.bindSurface] reached the tablet:
 * `viewModelScope` is wired to `Dispatchers.Main` and its autosave loop is only cancelled by
 * `onCleared`, so nothing about the editor could be driven from a unit test. Everything the
 * editor does is now a plain class taking a scope, and this holds the Android lifecycle.
 */
@HiltViewModel
class NoteEditorViewModel @Inject constructor(fs: FileSystem, clock: Clock) : ViewModel() {

    private val editor = NoteEditor(fs, clock, viewModelScope)

    val state: StateFlow<NoteEditorState> = editor.state

    fun onSurfaceAttached(surface: NoteEditor.NoteSurface) = editor.onSurfaceAttached(surface)

    fun onSurfaceDetached() = editor.onSurfaceDetached()

    fun undo() = editor.undo()

    fun redo() = editor.redo()

    fun showPage(pageId: PageId) = editor.showPage(pageId)

    fun goToPage(index: Int) = editor.goToPage(index)

    fun addPage(template: PageTemplate) = editor.addPage(template)

    fun duplicateCurrentPage() = editor.duplicateCurrentPage()

    fun deleteCurrentPage() = editor.deleteCurrentPage()

    fun movePage(toIndex: Int) = editor.movePage(toIndex)

    fun setTemplate(template: PageTemplate) = editor.setTemplate(template)

    fun dismissMessage() = editor.dismissMessage()

    /**
     * SPEC §5.1 — fold the journal into the bundle. Called from `Activity.onStop`, which is the
     * last point Android guarantees the process is alive.
     */
    fun materialize() = editor.materialize()

    override fun onCleared() {
        // Ends the autosave loop. Not a substitute for `materialize()` from the Activity —
        // `onCleared` is not guaranteed to run before the process dies, which is why the journal
        // exists at all (SPEC §5.1).
        editor.close()
        super.onCleared()
    }
}
