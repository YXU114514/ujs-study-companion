package cn.campusai.ujscompanion;

public final class SchoolConfig {
    private SchoolConfig() {}
    public static final String ORIGIN = "https://webvpn.ujs.edu.cn";
    public static final String LOGIN_URL = ORIGIN + "/login?cas_login=true";
    public static final String PROXY = ORIGIN + "/http/77726476706e69737468656265737421fae0598869256243300d8db9d6562d";
    public static final String TIMETABLE = "timetable";
    public static final String EXAMS = "exams";
    public static String page(String kind) {
        return TIMETABLE.equals(kind)
            ? PROXY + "/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
            : PROXY + "/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default";
    }
    public static String endpoint(String kind) {
        return TIMETABLE.equals(kind) ? "/kbcx/xskbcx_cxXsgrkb.html" : "/kwgl/kscx_cxXsksxxIndex.html";
    }
    public static String label(String kind) { return TIMETABLE.equals(kind) ? "课表" : "考试安排"; }
}
