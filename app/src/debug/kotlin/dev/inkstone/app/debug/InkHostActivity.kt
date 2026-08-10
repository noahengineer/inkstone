package dev.inkstone.app.debug

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import dev.inkstone.android.ink.InkSurfaceView

/**
 * A window with nothing in it but the ink surface. **Debug builds only.**
 *
 * `WetInkRemoteMergeTest` needs a laid-out [InkSurfaceView] inside a real window: wet ink is
 * front-buffered through a SurfaceView, so a stroke can only genuinely be *in progress* inside
 * one. Not `NoteActivity`, which wants a Hilt graph, a note id and an editor — none of which row
 * 10's wet half is about, and all of which would make a failure ambiguous.
 *
 * Declared in `src/debug` rather than `src/androidTest` because `ActivityScenario` cannot launch it
 * otherwise: an activity in the test APK belongs to `dev.inkstone.test`, and
 * `Instrumentation.startActivitySync` refuses with *"Intent in process dev.inkstone resolved to
 * different process dev.inkstone.test"*. Same reason [CrashPointService] lives here.
 */
class InkHostActivity : Activity() {

    lateinit var surface: InkSurfaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = InkSurfaceView(this)
        setContentView(
            surface,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }
}
