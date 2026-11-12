package cn.campusai.ujscompanion;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;

/** No background activity launches: an expired session produces a login notification. */
public final class SyncWorker extends Worker {
    public static final String KEY_KIND = "kind";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        if (isStopped()) return finished(false, false);
        // Clear-data uses this same monitor after canceling work. It therefore
        // cannot be followed by a stale worker's cache/error/notification write.
        synchronized (SyncEngine.class) {
            if (isStopped()) return finished(false, false);
            return synchronizeData();
        }
    }

    private Result synchronizeData() {
        Context context = getApplicationContext();
        String kind = getInputData().getString(KEY_KIND);
        if (!SchoolConfig.TIMETABLE.equals(kind) && !SchoolConfig.EXAMS.equals(kind)) {
            return Result.failure();
        }
        AppStore store = new AppStore(context);
        try {
            AppStore.Change change = SyncEngine.sync(context, kind);
            if (isStopped()) return finished(false, false);
            Notifier.clearLoginRequired(context);
            Notifier.changed(context, kind, change);
            return finished(true, false);
        } catch (SyncEngine.AuthException error) {
            if (isStopped()) return finished(false, false);
            store.setNeedsLogin(true);
            store.setError(kind, "登录已过期，请点开学校页面重新验证");
            Notifier.loginRequired(context);
            return finished(false, true);
        } catch (IOException error) {
            if (isStopped()) return finished(false, false);
            store.setError(kind, "暂时无法获取" + SchoolConfig.label(kind) + "，已保留上次数据");
            if (getRunAttemptCount() < 2 && !isStopped()) return Result.retry();
            return finished(false, false);
        } catch (RuntimeException error) {
            if (isStopped()) return finished(false, false);
            store.setError(kind, "本次" + SchoolConfig.label(kind) + "更新未完成，已保留上次数据");
            return finished(false, false);
        }
    }

    /** Terminal business failures do not cancel the other kind in a manual chain. */
    private Result finished(boolean ok, boolean loginRequired) {
        return Result.success(new Data.Builder().putBoolean("ok", ok)
                .putBoolean("loginRequired", loginRequired).build());
    }
}
