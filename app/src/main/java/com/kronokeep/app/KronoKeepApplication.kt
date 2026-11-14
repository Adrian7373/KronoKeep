package com.kronokeep.app

import android.app.Application
import androidx.work.Configuration
import com.kronokeep.app.data.reminders.AndroidReminderNotifier
import com.kronokeep.app.data.reminders.ReminderScheduler
import com.kronokeep.app.data.sync.KronoWorkerFactory
import com.kronokeep.app.data.sync.UploadTrigger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KronoKeepApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: KronoWorkerFactory
    @Inject lateinit var uploadTrigger: UploadTrigger
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var reminderNotifier: AndroidReminderNotifier

    // WorkManager's default initializer is removed in the manifest so it picks up this factory.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        reminderNotifier.ensureChannel()
        uploadTrigger.start()
        reminderScheduler.start()
    }
}
