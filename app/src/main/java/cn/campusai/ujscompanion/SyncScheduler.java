package cn.campusai.ujscompanion;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Persistent jobs; Android may defer execution during Doze or battery saving. */
public final class SyncScheduler {
    public static final String SYNC_TAG = "ujs.sync";
    public static final String MANUAL_WORK = "ujs.manual.sync";
    public static final String TIMETABLE_WORK = "ujs.periodic.timetable";
    public static final String EXAMS_WORK = "ujs.periodic.exams";
    private SyncScheduler() {}

    private static Constraints constraints() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    private static Data input(String kind) {
        return new Data.Builder().putString(SyncWorker.KEY_KIND, kind).build();
    }

    private static PeriodicWorkRequest periodic(String kind, long hours) {
        return new PeriodicWorkRequest.Builder(SyncWorker.class, hours, TimeUnit.HOURS)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setInputData(input(kind))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(SYNC_TAG).addTag(kind).build();
    }

    public static void schedule(Context context) {
        WorkManager work = WorkManager.getInstance(context.getApplicationContext());
        work.enqueueUniquePeriodicWork(TIMETABLE_WORK, ExistingPeriodicWorkPolicy.KEEP,
                periodic(SchoolConfig.TIMETABLE, 12));
        work.enqueueUniquePeriodicWork(EXAMS_WORK, ExistingPeriodicWorkPolicy.KEEP,
                periodic(SchoolConfig.EXAMS, 3));
    }

    private static OneTimeWorkRequest once(String kind) {
        return new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints()).setInputData(input(kind))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(SYNC_TAG).addTag(kind).build();
    }

    /** KEEP prevents repeated taps from queuing duplicate refresh chains. */
    public static void refresh(Context context) {
        WorkManager.getInstance(context.getApplicationContext())
                .beginUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, once(SchoolConfig.TIMETABLE))
                .then(once(SchoolConfig.EXAMS)).enqueue();
    }
}
