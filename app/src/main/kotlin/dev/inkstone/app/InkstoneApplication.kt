package dev.inkstone.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.inkstone.app.platform.WorkManagerScheduler
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class InkstoneApplication : Application() {

    /**
     * M5. The periodic outbox drain is registered here rather than from a screen, and
     * that placement is the point: SPEC §7.6's background poll exists for the device that
     * was offline when the last note was written and is never opened again before the
     * network comes back.
     *
     * Idempotent — [WorkManagerScheduler.ensurePeriodicDrain] uses
     * `ExistingPeriodicWorkPolicy.KEEP`, so this makes sure the work exists rather than
     * rescheduling it on every launch.
     *
     * ⚠️ A [Provider], not the scheduler itself. Field injection is **eager**: asking for
     * `WorkManagerScheduler` here builds `WorkManager` while the process is still starting, in
     * every process the app has — and WorkManager's initializer is a `ContentProvider`, which
     * Android creates only in the *default* process. So a secondary process died in
     * `Application.onCreate` before running a line of its own code. Found in M6 by the SPEC §13.3
     * crash harness, which needs a second process precisely so that killing it is survivable.
     */
    @Inject
    lateinit var workScheduler: Provider<WorkManagerScheduler>

    override fun onCreate() {
        super.onCreate()
        // Scheduling is per app, not per process, so anywhere but the default process this would
        // be redundant at best — and see the note above for what it actually did. The static
        // `Application.getProcessName()` is the only public way to ask; `Context` cannot.
        if (getProcessName() != packageName) return
        val scheduler = workScheduler.get()
        scheduler.ensurePeriodicDrain()
        // ⭐ M6's other half. The push side has a periodic safety net for the device that was
        // offline when the last note was written; the pull side needs one for the device that is
        // *never opened* while a peer is editing — an edit made on the other tablet has to reach
        // this one even if nobody launches the app for a day. Fifteen minutes is WorkManager's
        // floor; the sixty-second figure SPEC §7.6 asks for is `PollTicker`'s, and it only runs
        // while a screen is in front.
        scheduler.ensurePeriodicReconcile()
        // SPEC §8.3's backlog. Registered here rather than from a screen for the same reason the
        // other two are: a note the user never reopens still has to become searchable, and a
        // registration that only happens when someone opens the library is a registration that
        // does not happen on the device that most needs it.
        scheduler.ensurePeriodicRecognition()
    }
}
