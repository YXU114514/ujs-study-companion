package cn.campusai.ujscompanion;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.work.Configuration;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.time.LocalDate;

import static org.junit.Assert.*;

/** UI behavior using synthetic data only. No school login or network request. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityTest {
    private Application app;
    private AppStore store;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            new File(app.getFilesDir(), "companion-cache-v1.json" + suffix).delete();
        }
        app.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        store = new AppStore(app);
        try { WorkManager.getInstance(app); }
        catch (IllegalStateException uninitialized) {
            WorkManager.initialize(app, new Configuration.Builder().build());
        }
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        WorkManager.getInstance(app).cancelAllWork();
    }

    private void fixture(String year, String term, boolean calibrated) throws Exception {
        JSONObject course = new JSONObject().put("kcmc", "测试课程甲").put("xm", "测试教师")
                .put("xqj", "2").put("jcs", "3-4").put("zcd", "1-20周")
                .put("cdmc", "测试教室").put("xnm", year).put("xqm", term);
        JSONObject timetable = new JSONObject().put("kbList", new JSONArray().put(course))
                .put("sjkList", new JSONArray());
        store.saveData("timetable", timetable, year, term);
        JSONObject exam = new JSONObject().put("kcmc", "测试考试乙").put("ksmc", "测试期末")
                .put("kssj", "2026-12-20 09:00-11:00").put("cdmc", "测试考场")
                .put("xnm", year).put("xqm", term);
        JSONObject exams = new JSONObject().put("items", new JSONArray().put(exam))
                .put("totalCount", 1).put("totalResult", 1).put("totalPage", 1);
        store.saveData("exams", exams, year, term);
        if (calibrated) {
            LocalDate first = SemesterClock.mondayForWeek(11, SemesterClock.today());
            store.saveAnchor(year, term, first.toString(), 20);
        }
    }

    private void launch(Bundle state) {
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();
        activity = controller.get();
        layout();
    }

    private void layout() {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
    }

    private View content() { return activity.getWindow().getDecorView(); }

    private TextView findText(View view, String exact) {
        if (view instanceof TextView && exact.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), exact);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findClass(View view, String simpleName) {
        if (view.getClass().getSimpleName().equals(simpleName)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClass(group.getChildAt(i), simpleName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void click(String text) {
        TextView button = findText(content(), text);
        assertNotNull("Missing button: " + text, button);
        assertTrue(button.performClick());
        layout();
    }

    private void swipe(boolean right) {
        View frame = findClass(content(), "SwipeFrame");
        assertNotNull("Timetable swipe area is missing", frame);
        float density = activity.getResources().getDisplayMetrics().density;
        float delta = 90 * density;
        float middle = frame.getWidth() / 2f;
        float start = right ? middle - delta / 2 : middle + delta / 2;
        float end = right ? middle + delta / 2 : middle - delta / 2;
        float y = Math.min(frame.getHeight() / 2f, 100 * density);
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, start, y, 0);
        MotionEvent move = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_MOVE, end, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 200, MotionEvent.ACTION_UP, end, y, 0);
        try {
            frame.dispatchTouchEvent(down);
            frame.dispatchTouchEvent(move);
            frame.dispatchTouchEvent(up);
        } finally { down.recycle(); move.recycle(); up.recycle(); }
        layout();
    }

    @Test public void calibratedWeekAndBottomTabsAreVisible() throws Exception {
        fixture("2026", "3", true);
        launch(null);
        assertNotNull(findText(content(), "第 11 周  ·  本周"));
        assertNotNull(findText(content(), "▦   课表"));
        assertNotNull(findText(content(), "☷   考试"));
        assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
        click("☷   考试");
        assertNotNull(findText(content(), "考试安排"));
        assertNotNull(findText(content(), "测试考试乙"));
        assertNull(findClass(content(), "TimetableCanvas"));
        click("▦   课表");
        assertNotNull(findClass(content(), "TimetableCanvas"));
        assertNotNull(findText(content(), "第 11 周  ·  本周"));
    }

    @Test public void leftSwipeAdvancesWeekAndRightSwipeReturns() throws Exception {
        fixture("2026", "3", true);
        launch(null);
        swipe(false);
        assertNotNull(findText(content(), "第 12 周"));
        assertNull(findText(content(), "测试考试乙"));
        swipe(true);
        assertNotNull(findText(content(), "第 11 周  ·  本周"));
    }

    @Test public void missingAnchorDoesNotClaimWeekOneIsCurrent() throws Exception {
        fixture("2027", "12", false);
        launch(null);
        assertNotNull(findText(content(), "设置学期日期"));
        assertNotNull(findText(content(), "第 1 周"));
        assertNotNull(findText(content(), "校准"));
        assertNull(findText(content(), "第 1 周  ·  本周"));
    }

    @Test public void emptyRestoredActivityShowsLoginWithoutCrashing() {
        launch(new Bundle());
        assertNotNull(findText(content(), "让日程自己更新"));
        assertNotNull(findText(content(), "登录江苏大学"));
        assertNotNull(findText(content(), "▦   课表"));
        assertNotNull(findText(content(), "☷   考试"));
    }

    @Test public void notificationIntentCanSelectEitherBottomTab() throws Exception {
        fixture("2026", "3", true);
        launch(null);
        activity.onNewIntent(new Intent(activity, MainActivity.class).putExtra("tab", "exams"));
        layout();
        assertNotNull(findText(content(), "测试考试乙"));
        activity.onNewIntent(new Intent(activity, MainActivity.class).putExtra("tab", "timetable"));
        layout();
        assertNotNull(findClass(content(), "TimetableCanvas"));
    }
}
