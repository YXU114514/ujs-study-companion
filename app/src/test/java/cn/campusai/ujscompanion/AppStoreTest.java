package cn.campusai.ujscompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AppStoreTest {
    private AppStore store;

    @Before public void setup() {
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            new File(files, "companion-cache-v1.json" + suffix).delete();
        }
        store = new AppStore(RuntimeEnvironment.getApplication());
    }

    private JSONObject exam(String course, String place) throws Exception {
        return new JSONObject().put("kcmc", course).put("cdmc", place).put("ksmc", "期末考试")
                .put("kssj", "2026-12-20 09:00-11:00").put("xnm", "2026").put("xqm", "3")
                .put("xm", "PRIVATE_STUDENT").put("xh", "PRIVATE_ID");
    }

    private JSONObject exams(JSONObject... rows) throws Exception {
        JSONArray items = new JSONArray();
        for (JSONObject row : rows) items.put(row);
        return new JSONObject().put("items", items).put("totalCount", rows.length)
                .put("totalResult", rows.length).put("totalPage", rows.length == 0 ? 0 : 1);
    }

    @Test public void initialImportDoesNotNotifyAndOmitsStudentIdentity() throws Exception {
        AppStore.Change change = store.saveData("exams", exams(exam("数学", "A101")), "2026", "3");
        assertTrue(change.initial);
        assertFalse(change.changed);
        assertEquals(0, change.added);
        assertFalse(store.data("exams").toString().contains("PRIVATE_"));
        assertTrue(store.lastSuccess("exams") > 0);
        assertFalse(store.needsLogin());
    }

    @Test public void orderDoesNotChangeButDuplicatesAndFieldsDo() throws Exception {
        JSONObject a = exam("数学", "A101"), b = exam("英语", "B101");
        store.saveData("exams", exams(a, b, b), "2026", "3");
        assertFalse(store.saveData("exams", exams(b, a, b), "2026", "3").changed);
        AppStore.Change removed = store.saveData("exams", exams(a, b), "2026", "3");
        assertEquals(0, removed.added);
        assertEquals(1, removed.removed);
        AppStore.Change relocated = store.saveData("exams", exams(exam("数学", "A102"), b), "2026", "3");
        assertEquals(1, relocated.added);
        assertEquals(1, relocated.removed);
    }

    @Test public void incompleteOrMalformedResponseNeverReplacesCache() throws Exception {
        store.saveData("exams", exams(exam("数学", "A101")), "2026", "3");
        String before = store.data("exams").toString();
        JSONObject partial = exams(exam("数学", "A101")).put("totalCount", 20).put("totalResult", 20).put("totalPage", 2);
        try { store.saveData("exams", partial, "2026", "3"); fail("partial response accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(before, store.data("exams").toString());
        try { store.saveData("exams", new JSONObject().put("code", "NOT_LOGIN"), "2026", "3"); fail("login response accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(before, store.data("exams").toString());
    }

    @Test public void verifiedMultiPageAggregateIsAcceptedButMarkerIsNotExported() throws Exception {
        JSONObject all = exams(exam("数学", "A101"), exam("英语", "B101"))
                .put("totalPage", 2).put("_allPagesFetched", true);
        store.saveData("exams", all, "2026", "3");
        assertEquals(2, store.data("exams").getJSONArray("exams").length());
        assertFalse(store.data("exams").has("_allPagesFetched"));
    }

    @Test public void calibrationBelongsOnlyToItsSemesterAndSurvivesSync() throws Exception {
        assertEquals("", store.firstMonday("", ""));
        assertEquals(60, store.totalWeeks("", ""));
        assertEquals("2026-08-31", store.firstMonday("2026", "3"));
        assertEquals("", store.firstMonday("2026", "12"));
        assertEquals("", store.firstMonday("2027", "3"));
        store.saveAnchor("2026", "3", "2026-09-07", 20);
        store.saveData("exams", exams(), "2026", "3");
        assertEquals("2026-09-07", store.firstMonday("2026", "3"));
        assertEquals(20, store.totalWeeks("2026", "3"));
        assertEquals(60, store.totalWeeks("2026", "12"));
    }

    @Test public void timetableValidatesWithoutDroppingPracticalOrKeepingIdentity() throws Exception {
        JSONObject row = new JSONObject().put("kcmc", "物理").put("xm", "教师")
                .put("xqj", "2").put("jcs", "3-4").put("zcd", "1-15周(单)")
                .put("userModel", new JSONObject().put("password", "PRIVATE_SECRET"));
        JSONObject practice = new JSONObject().put("kcmc", "课程设计").put("qsjsz", "17-18");
        JSONObject raw = new JSONObject().put("kbList", new JSONArray().put(row))
                .put("sjkList", new JSONArray().put(practice)).put("xsxx", "PRIVATE_STUDENT");
        store.saveData("timetable", raw, "2026", "3");
        JSONObject data = store.data("timetable");
        assertEquals("1-15周(单)", data.getJSONArray("courses").getJSONObject(0).getString("weeks"));
        assertEquals(1, data.getJSONArray("practical").length());
        assertFalse(data.toString().contains("PRIVATE_"));
        String before = data.toString();
        row.put("zcd", "未知周次");
        try { store.saveData("timetable", raw, "2026", "3"); fail("invalid weeks accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(before, store.data("timetable").toString());
    }
}
