package dev.inkstone.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.inkstone.app.platform.WorkManagerScheduler
import javax.inject.Inject

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
     */
    @Inject
    lateinit var workScheduler: WorkManagerScheduler

    override fun onCreate() {
        super.onCreate()
        workScheduler.ensurePeriodicDrain()
    }
}
