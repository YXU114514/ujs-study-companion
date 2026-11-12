package cn.campusai.ujscompanion;

import android.app.NotificationManager;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

/** Real notification lifecycle behavior: first imports are silent, login reminders deduplicate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NotifierTest {
    private Context context;
    private NotificationManager manager;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(NotificationManager.class);
        manager.cancelAll();
        Notifier.clearLoginRequired(context);
    }

    @Test public void initialImportDoesNotNotifyButSubsequentChangeDoes() {
        Notifier.changed(context, SchoolConfig.TIMETABLE, new AppStore.Change(true, true, 12, 0));
        assertEquals(0, manager.getActiveNotifications().length);
        Notifier.changed(context, SchoolConfig.TIMETABLE, new AppStore.Change(false, false, 0, 0));
        assertEquals(0, manager.getActiveNotifications().length);
        Notifier.changed(context, SchoolConfig.TIMETABLE, new AppStore.Change(false, true, 1, 1));
        assertEquals(1, manager.getActiveNotifications().length);
    }

    @Test public void dismissedLoginReminderIsNotRepeatedUntilSessionRecovers() {
        Notifier.loginRequired(context);
        assertEquals(1, manager.getActiveNotifications().length);
        manager.cancelAll(); // A user dismisses it; repeated workers must not nag.
        Notifier.loginRequired(context);
        assertEquals(0, manager.getActiveNotifications().length);
        Notifier.clearLoginRequired(context);
        Notifier.loginRequired(context);
        assertEquals(1, manager.getActiveNotifications().length);
    }
}
