package cn.campusai.ujscompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline-first timetable and exams. No credentials or query payloads in this UI. */
public class MainActivity extends Activity {
    static final int BG = Color.rgb(245,248,246), INK = Color.rgb(24,55,47);
    static final int GREEN = Color.rgb(17,113,86), MUTED = Color.rgb(112,132,124);
    static final int PALE = Color.rgb(226,241,233);
    static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Shanghai");
    private AppStore store;
    private LinearLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String tab = SchoolConfig.TIMETABLE, signature = "", lastDay = "";
    private int viewedWeek = -1;
    private final Runnable watcher = new Runnable() {
        @Override public void run() {
            String today = today().toString();
            if (!today.equals(lastDay)) { viewedWeek = -1; lastDay = today; signature = ""; }
            String next = store.lastSuccess("timetable")+":"+store.lastSuccess("exams")+":"+
                    store.needsLogin()+":"+store.error("timetable")+":"+store.error("exams");
            if (!next.equals(signature)) { signature = next; render(); }
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AppStore(this);
        if (state != null) { tab = state.getString("tab", tab); viewedWeek = state.getInt("week", -1); lastDay = state.getString("day", today().toString()); }
        else lastDay = today().toString();
        readIntent(getIntent());
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            } else { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); }
            return insets;
        });
        setContentView(root);
        if (store.data("timetable") != null || store.data("exams") != null) SyncScheduler.schedule(this);
        render();
        if (state == null && store.data("timetable") == null && store.data("exams") == null
                && !getPreferences(0).getBoolean("firstLoginShown", false)) {
            getPreferences(0).edit().putBoolean("firstLoginShown",true).apply();
            root.post(this::login);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("tab",tab); out.putInt("week",viewedWeek); out.putString("day",lastDay); super.onSaveInstanceState(out);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent); render(); }
    private void readIntent(Intent intent) {
        if (intent == null) return;
        String destination = intent.getStringExtra("tab");
        if (SchoolConfig.EXAMS.equals(destination) || SchoolConfig.TIMETABLE.equals(destination)) tab = destination;
    }
    @Override protected void onResume() {
        super.onResume();
        if (root == null) return;
        handler.removeCallbacks(watcher); watcher.run();
        if ((store.data("timetable") != null || store.data("exams") != null) && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(0).getBoolean("notificationAsked",false)) {
            getPreferences(0).edit().putBoolean("notificationAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }
    @Override protected void onPause() { handler.removeCallbacks(watcher); super.onPause(); }
    private LocalDate today() { return Instant.now().atZone(SCHOOL_ZONE).toLocalDate(); }
    private void login() { startActivity(new Intent(this,LoginActivity.class)); }
    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+.5f); }
    private GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d; }
    private TextView text(String s,int size,int color,boolean bold) {
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;
    }
    private TextView button(String s,Runnable action,boolean filled) {
        TextView v=text(s,14,filled?Color.WHITE:GREEN,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(14),dp(10),dp(14),dp(10));
        v.setBackground(shape(filled?GREEN:PALE,14));v.setOnClickListener(w->action.run());v.setMinHeight(dp(44));return v;
    }
    private LinearLayout column() {LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;}
    private LinearLayout row() {LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.HORIZONTAL);x.setGravity(Gravity.CENTER_VERTICAL);return x;}
    private void gap(LinearLayout parent,int h) {View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private JSONObject current() {JSONObject d=store.data(tab);return d!=null?d:(store.data("timetable")!=null?store.data("timetable"):store.data("exams"));}
    private String year() {JSONObject d=current();return d==null?"":d.optString("year");}
    private String term() {JSONObject d=current();return d==null?"":d.optString("term");}
    private Integer currentWeek() {return SemesterClock.teachingWeek(store.firstMonday(year(),term()),store.totalWeeks(year(),term()),today());}
    private String semesterLabel() {
        String y=year(),t=term(); if(y.isEmpty())return "你的校园日程，自动更新";
        try {y=y+"—"+(Integer.parseInt(y)+1);}catch(Exception ignored){}
        return y+" · "+((t.equals("3")||t.equals("1"))?"第一学期":(t.equals("12")||t.equals("2"))?"第二学期":"当前学期");
    }

    private void render() {
        if(root==null)return;
        root.removeAllViews();
        LinearLayout head=column();head.setPadding(dp(20),dp(16),dp(20),dp(12));
        LinearLayout brand=row();LinearLayout labels=column();
        labels.addView(text("江大学伴",24,INK,true));labels.addView(text(semesterLabel(),12,MUTED,false));
        brand.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        brand.addView(button("刷新",()->{if(store.needsLogin())login();else {SyncScheduler.refresh(this);Toast.makeText(this,"已安排刷新",Toast.LENGTH_SHORT).show();}},false));
        TextView more=button("···",this::settings,false);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(46),dp(44));mp.leftMargin=dp(8);brand.addView(more,mp);
        head.addView(brand);gap(head,12);
        String updated=formatUpdated(store.lastSuccess(tab));
        head.addView(text("●  "+updated+"    ·    "+(tab.equals("timetable")?"每 12 小时更新":"每 3 小时更新"),11,MUTED,false));
        root.addView(head);

        if(store.needsLogin()) {
            LinearLayout banner=row();banner.setPadding(dp(16),dp(6),dp(16),dp(6));banner.setBackgroundColor(Color.rgb(255,242,218));
            TextView message=text("学校登录已失效，已保留上次数据",12,Color.rgb(137,88,24),false);
            banner.addView(message,new LinearLayout.LayoutParams(0,-2,1));banner.addView(button("重新登录",this::login,false));root.addView(banner);
        } else if(!store.error(tab).isEmpty()) {
            TextView warning=text("本次未更新："+store.error(tab),11,Color.rgb(153,101,41),false);warning.setPadding(dp(20),0,dp(20),dp(8));root.addView(warning);
        }

        FrameLayout body=new FrameLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        JSONObject data=store.data(tab);
        if(data==null) {body.addView(emptyState(),new FrameLayout.LayoutParams(-1,-1));}
        else if(tab.equals("timetable")) {body.addView(timetable(data),new FrameLayout.LayoutParams(-1,-1));}
        else {body.addView(exams(data),new FrameLayout.LayoutParams(-1,-1));}

        LinearLayout bottom=row();bottom.setPadding(dp(18),dp(10),dp(18),dp(10));bottom.setBackgroundColor(BG);
        LinearLayout pill=row();pill.setPadding(dp(5),dp(5),dp(5),dp(5));pill.setBackground(shape(Color.rgb(231,237,232),22));
        for(String kind:new String[]{"timetable","exams"}) {
            boolean active=tab.equals(kind);TextView item=text(kind.equals("timetable")?"▦   课表":"☷   考试",15,active?Color.WHITE:MUTED,true);
            item.setGravity(Gravity.CENTER);if(active)item.setBackground(shape(GREEN,18));
            item.setOnClickListener(v->{tab=kind;signature="";render();});
            pill.addView(item,new LinearLayout.LayoutParams(0,dp(48),1));
        }
        bottom.addView(pill,new LinearLayout.LayoutParams(-1,-2));root.addView(bottom);
    }

    private View emptyState() {
        LinearLayout box=column();box.setGravity(Gravity.CENTER);box.setPadding(dp(30),dp(24),dp(30),dp(24));
        TextView icon=text("▦",60,GREEN,true);icon.setGravity(Gravity.CENTER);box.addView(icon);
        TextView title=text("让日程自己更新",23,INK,true);title.setGravity(Gravity.CENTER);box.addView(title);gap(box,12);
        TextView hint=text("首次在学校页面登录，即可获取个人课表和考试安排。\n之后自动更新，有变动会提醒你。",14,MUTED,false);hint.setGravity(Gravity.CENTER);hint.setLineSpacing(dp(5),1);box.addView(hint);gap(box,24);
        box.addView(button("登录江苏大学",this::login,true));return box;
    }

    private View timetable(JSONObject data) {
        Integer current=currentWeek();if(viewedWeek<1)viewedWeek=current==null?1:current;
        LinearLayout content=column();
        LinearLayout toolbar=row();toolbar.setPadding(dp(16),dp(6),dp(16),dp(10));
        toolbar.addView(button("‹",()->moveWeek(-1),false));
        LinearLayout weekLabel=column();weekLabel.setGravity(Gravity.CENTER);
        TextView week=text("第 "+viewedWeek+" 周"+(current!=null&&current==viewedWeek?"  ·  本周":""),21,INK,true);week.setGravity(Gravity.CENTER);weekLabel.addView(week);
        String monday=store.firstMonday(year(),term());String subtitle;
        try {LocalDate start=LocalDate.parse(monday).plusWeeks(viewedWeek-1);subtitle=start.format(DateTimeFormatter.ofPattern("M月d日"))+" — "+start.plusDays(6).format(DateTimeFormatter.ofPattern("M月d日"));}
        catch(Exception e){subtitle="设置本周周次后自动跟进日期";}
        TextView range=text(subtitle,11,MUTED,false);range.setGravity(Gravity.CENTER);weekLabel.addView(range);
        toolbar.addView(weekLabel,new LinearLayout.LayoutParams(0,-2,1));
        toolbar.addView(button(current==null?"校准":"本周",()->{if(currentWeek()==null)calibrate();else{viewedWeek=currentWeek();render();}},false));
        TextView next=button("›",()->moveWeek(1),false);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);np.leftMargin=dp(6);toolbar.addView(next,np);content.addView(toolbar);
        TextView swipe=text("向左滑看下一周  ·  向右滑看上一周",10,MUTED,false);swipe.setGravity(Gravity.CENTER);content.addView(swipe);
        if(current==null){TextView note=text(SemesterClock.phase(monday,store.totalWeeks(year(),term()),today()),11,GREEN,false);note.setGravity(Gravity.CENTER);note.setPadding(0,dp(4),0,dp(4));content.addView(note);}
        SwipeFrame swipeFrame=new SwipeFrame();content.addView(swipeFrame,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setPadding(dp(8),dp(12),dp(8),dp(16));swipeFrame.addView(scroll);
        LinearLayout inner=column();scroll.addView(inner);
        JSONArray courses=data.optJSONArray("courses");if(courses==null)courses=new JSONArray();
        inner.addView(new TimetableCanvas(courses,viewedWeek,monday),new LinearLayout.LayoutParams(-1,dp(54+14*58)));
        JSONArray practical=data.optJSONArray("practical");
        if(practical!=null&&practical.length()>0){gap(inner,18);TextView h=text("  实践与其他安排",16,INK,true);inner.addView(h);gap(inner,8);
            for(int i=0;i<practical.length();i++){JSONObject p=practical.optJSONObject(i);if(p!=null)inner.addView(infoCard(p.optString("course"),p.optString("weeks")+"  ·  "+p.optString("teacher"),"具体时间请以学院安排为准"));}}
        List<JSONObject> unparsed=new ArrayList<>();for(int i=0;i<courses.length();i++){JSONObject c=courses.optJSONObject(i);if(c!=null&&(WeekPattern.parse(c.optString("weeks")).isEmpty()||periods(c.optString("periods"))[0]<1))unparsed.add(c);}
        if(!unparsed.isEmpty()){gap(inner,12);inner.addView(text("  以下安排的周次或节次需核对",13,MUTED,true));for(JSONObject c:unparsed)inner.addView(infoCard(c.optString("course"),c.optString("weeks")+" · "+c.optString("periods"),c.optString("location")));}
        return content;
    }

    private void moveWeek(int delta){int max=store.totalWeeks(year(),term());int w=Math.max(1,Math.min(max,viewedWeek+delta));if(w!=viewedWeek){viewedWeek=w;render();}}
    private int[] periods(String source){Matcher m=Pattern.compile("\\d+").matcher(source);List<Integer> n=new ArrayList<>();while(m.find()){int v=Integer.parseInt(m.group());if(v>=1&&v<=14)n.add(v);}return n.isEmpty()?new int[]{0,0}:new int[]{n.get(0),n.get(n.size()-1)};}

    private View exams(JSONObject data) {
        ScrollView scroll=new ScrollView(this);scroll.setPadding(dp(18),dp(4),dp(18),dp(16));scroll.setClipToPadding(false);
        LinearLayout list=column();scroll.addView(list);
        JSONArray array=data.optJSONArray("exams");List<JSONObject> rows=new ArrayList<>();if(array!=null)for(int i=0;i<array.length();i++)if(array.optJSONObject(i)!=null)rows.add(array.optJSONObject(i));
        rows.sort(Comparator.comparing(x->{LocalDateTime dt=examDate(x.optString("time"));return dt==null?LocalDateTime.MAX:dt;}));
        LinearLayout summary=column();summary.setPadding(dp(20),dp(19),dp(20),dp(19));summary.setBackground(shape(PALE,22));
        summary.addView(text("考试安排",23,INK,true));summary.addView(text(rows.isEmpty()?"当前查询没有考试安排":"已获取 "+rows.size()+" 条安排",13,GREEN,false));list.addView(summary);gap(list,18);
        if(rows.isEmpty())list.addView(infoCard("暂时没有考试","学校发布新安排后会自动更新","以教务系统最新发布为准"));
        for(JSONObject exam:rows){
            LocalDateTime date=examDate(exam.optString("time"));String tag="考试信息";
            if(date!=null){long days=ChronoUnit.DAYS.between(today(),date.toLocalDate());tag=days<0?"已结束":days==0?"今天考试":"距考试 "+days+" 天";}
            LinearLayout card=column();card.setPadding(dp(18),dp(16),dp(18),dp(16));card.setBackground(shape(Color.WHITE,20));
            card.addView(text(tag,11,GREEN,true));gap(card,7);card.addView(text(exam.optString("course"),19,INK,true));gap(card,12);
            TextView time=text("时间  "+exam.optString("time","待公布"),14,INK,false);time.setLineSpacing(dp(3),1);card.addView(time);gap(card,7);
            card.addView(text("地点  "+exam.optString("campus")+" "+exam.optString("location","待公布"),13,MUTED,false));
            String name=exam.optString("exam");if(!name.isEmpty()){gap(card,7);card.addView(text(name,11,MUTED,false));}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);list.addView(card,lp);
        }
        return scroll;
    }
    private LocalDateTime examDate(String s){try{Matcher m=Pattern.compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2}).*?(\\d{1,2}):(\\d{2})").matcher(s);if(m.find())return LocalDateTime.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4)),Integer.parseInt(m.group(5)));}catch(Exception ignored){}return null;}
    private View infoCard(String title,String line,String note){LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(shape(Color.WHITE,16));card.addView(text(title,15,INK,true));gap(card,6);card.addView(text(line,12,MUTED,false));if(!note.isEmpty()){gap(card,3);card.addView(text(note,11,MUTED,false));}LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);card.setLayoutParams(p);return card;}
    private String formatUpdated(long time){if(time<=0)return "等待首次获取";return "更新于 "+Instant.ofEpochMilli(time).atZone(SCHOOL_ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));}

    private void settings(){
        String[] choices={"校准今天是第几周","设置第一教学周周一","设置学期总周数","学校登录与重新验证","通知与后台运行设置","关于自动更新","清除本机数据"};
        new AlertDialog.Builder(this).setTitle("日程设置").setItems(choices,(d,which)->{
            if(which==0)calibrate();else if(which==1)chooseMonday();else if(which==2)totalWeeks();else if(which==3)login();
            else if(which==4)startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+getPackageName())));
            else if(which==5)new AlertDialog.Builder(this).setTitle("自动更新与本周追踪").setMessage("课表每 12 小时检查，考试每 3 小时检查。省电模式、无网络或系统后台限制会推迟执行；强制停止后需重新打开应用。\n\n首次获取建立基线，有变动才发送通知。学校要求验证码时，会提醒你重新登录。\n\n周次始终按北京时间计算。当前第一周周一："+store.firstMonday(year(),term())+"\n2026 年秋季默认日期沿用旧版校准；其他学期需要重新校准。").setPositiveButton("知道了",null).show();
            else new AlertDialog.Builder(this).setTitle("清除本机数据？").setMessage("将删除本应用保存的课表、考试和登录状态。不会影响旧版江大学伴。").setNegativeButton("取消",null).setPositiveButton("清除",(x,y)->resetLocalData()).show();
        }).setNegativeButton("关闭",null).show();
    }
    private void resetLocalData() {
        AlertDialog waiting = new AlertDialog.Builder(this).setMessage("正在结束同步并清除本机数据…").setCancelable(false).create();
        waiting.show();
        new Thread(() -> {
            try {
                androidx.work.WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(SyncScheduler.SYNC_TAG).getResult().get();
                synchronized (SyncEngine.class) {
                    store.reset();
                    java.util.concurrent.CountDownLatch cleared = new java.util.concurrent.CountDownLatch(1);
                    runOnUiThread(() -> android.webkit.CookieManager.getInstance().removeAllCookies(done -> cleared.countDown()));
                    if (!cleared.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Cookie cleanup timeout");
                    android.webkit.CookieManager.getInstance().flush();
                }
                Notifier.clearLoginRequired(getApplicationContext());
                ((android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancelAll();
                runOnUiThread(() -> {android.webkit.WebStorage.getInstance().deleteAllData();viewedWeek=-1;signature="";waiting.dismiss();render();});
            } catch (Exception error) {
                runOnUiThread(() -> {waiting.dismiss();Toast.makeText(this,"清除未完成，请稍后重试",Toast.LENGTH_LONG).show();});
            }
        }, "ujs-reset").start();
    }
    private boolean hasSemester(){if(year().isEmpty()||term().isEmpty()){Toast.makeText(this,"请先登录获取当前学期",Toast.LENGTH_LONG).show();return false;}return true;}
    private void calibrate(){if(!hasSemester())return;EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("输入今天所在的教学周，例如 11");Integer w=currentWeek();if(w!=null)input.setText(String.valueOf(w));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("今天是第几周？").setMessage("以北京时间今天为准，之后每天自动跟进。").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{try{int n=Integer.parseInt(input.getText().toString());if(n<1||n>store.totalWeeks(year(),term()))throw new Exception();store.saveAnchor(year(),term(),SemesterClock.mondayForWeek(n,today()).toString(),store.totalWeeks(year(),term()));viewedWeek=-1;render();d.dismiss();}catch(Exception e){input.setError("请输入有效周次（1—"+store.totalWeeks(year(),term())+"）");}}));d.show();}
    private void chooseMonday(){if(!hasSemester())return;LocalDate initial=today();try{initial=LocalDate.parse(store.firstMonday(year(),term()));}catch(Exception ignored){}DatePickerDialog d=new DatePickerDialog(this,(picker,y,m,day)->{LocalDate date=LocalDate.of(y,m+1,day);if(date.getDayOfWeek().getValue()!=1){Toast.makeText(this,"请选择第一教学周的周一",Toast.LENGTH_LONG).show();return;}store.saveAnchor(year(),term(),date.toString(),store.totalWeeks(year(),term()));viewedWeek=-1;render();},initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth());d.setTitle("第一教学周的周一");d.show();}
    private void totalWeeks(){if(!hasSemester())return;EditText input=new EditText(this);input.setInputType(2);input.setText(String.valueOf(store.totalWeeks(year(),term())));AlertDialog d=new AlertDialog.Builder(this).setTitle("本学期总周数").setMessage("设置后，超过此范围会显示学期已结束。未设置时最多显示 60 周。").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{try{int n=Integer.parseInt(input.getText().toString());if(n<1||n>60)throw new Exception();String first=store.firstMonday(year(),term());if(first==null||first.isEmpty()){input.setError("请先设置学期第一周");return;}store.saveAnchor(year(),term(),first,n);viewedWeek=-1;render();d.dismiss();}catch(Exception e){input.setError("请输入 1—60");}}));d.show();}

    private class SwipeFrame extends FrameLayout {
        float x,y;boolean horizontal;
        SwipeFrame(){super(MainActivity.this);}
        @Override public boolean onInterceptTouchEvent(MotionEvent e){if(e.getActionMasked()==0){x=e.getX();y=e.getY();horizontal=false;}else if(e.getActionMasked()==2){float dx=e.getX()-x,dy=e.getY()-y;if(Math.abs(dx)>dp(42)&&Math.abs(dx)>Math.abs(dy)*1.4f){horizontal=true;return true;}}return super.onInterceptTouchEvent(e);}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==1&&horizontal){float dx=e.getX()-x;if(Math.abs(dx)>dp(48))moveWeek(dx<0?1:-1);horizontal=false;return true;}return horizontal||super.onTouchEvent(e);}
    }

    private class TimetableCanvas extends View {
        final List<JSONObject> visible=new ArrayList<>();final List<RectF> bounds=new ArrayList<>();final Paint paint=new Paint(3);final int week;final String anchor;
        final int[] lanes, widths;
        final int[] colors={0xffd8eee0,0xffdcebf4,0xfff5e8d0,0xffe9e0f2,0xfff5ded7,0xffd8eceb};
        TimetableCanvas(JSONArray data,int w,String first){super(MainActivity.this);week=w;anchor=first;setContentDescription("第"+w+"周课表，向左滑查看下一周，向右滑查看上一周");
            for(int i=0;i<data.length();i++){JSONObject c=data.optJSONObject(i);if(c!=null&&WeekPattern.includes(c.optString("weeks"),w)&&periods(c.optString("periods"))[0]>0)visible.add(c);}
            visible.sort(Comparator.comparingInt(c->c.optInt("weekday",0)*100+periods(c.optString("periods"))[0]));
            lanes=new int[visible.size()];widths=new int[visible.size()];
            for(int day=1;day<=7;day++){
                List<Integer> group=new ArrayList<>();int end=0;
                for(int i=0;i<visible.size();i++)if(visible.get(i).optInt("weekday")==day){int[] p=periods(visible.get(i).optString("periods"));if(!group.isEmpty()&&p[0]>end){layoutGroup(group);group.clear();end=0;}group.add(i);end=Math.max(end,p[1]);}
                layoutGroup(group);
            }
        }
        private void layoutGroup(List<Integer> group){List<Integer> ends=new ArrayList<>();for(int index:group){int[] p=periods(visible.get(index).optString("periods"));int lane=0;while(lane<ends.size()&&ends.get(lane)>=p[0])lane++;if(lane==ends.size())ends.add(p[1]);else ends.set(lane,p[1]);lanes[index]=lane;}for(int index:group)widths[index]=Math.max(1,ends.size());}
        private void label(Canvas canvas,String s,float x,float y,float size,int color,boolean bold){paint.setTextSize(dp(size));paint.setColor(color);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);canvas.drawText(s,x,y,paint);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float left=dp(28),col=(getWidth()-left)/7f,top=dp(52),rh=dp(58);bounds.clear();
            paint.setColor(Color.WHITE);canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(18),dp(18),paint);
            LocalDate first=null;try{first=LocalDate.parse(anchor).plusWeeks(week-1);}catch(Exception ignored){}
            for(int d=1;d<=7;d++){float x=left+(d-1)*col;boolean isToday=first!=null&&first.plusDays(d-1).equals(today());
                if(isToday){paint.setColor(0xffeef7f0);canvas.drawRect(x,0,x+col,getHeight(),paint);}
                label(canvas,new String[]{"一","二","三","四","五","六","日"}[d-1],x+col/2-dp(5),dp(20),12,isToday?GREEN:INK,true);
                if(first!=null)label(canvas,first.plusDays(d-1).format(DateTimeFormatter.ofPattern("M/d")),x+dp(9),dp(39),9,MUTED,false);
            }
            paint.setStrokeWidth(dp(.5f));for(int r=0;r<=14;r++){paint.setColor(0xffedf1ed);canvas.drawLine(0,top+r*rh,getWidth(),top+r*rh,paint);if(r<14)label(canvas,String.valueOf(r+1),dp(7),top+r*rh+dp(27),10,MUTED,false);}
            for(int i=0;i<visible.size();i++){JSONObject c=visible.get(i);int day=c.optInt("weekday",0);int[] periods=periods(c.optString("periods"));if(day<1||day>7){bounds.add(new RectF());continue;}
                float width=(col-dp(4))/widths[i];float x=left+(day-1)*col+dp(2)+lanes[i]*width;
                RectF rect=new RectF(x,top+(periods[0]-1)*rh+dp(2),x+width-dp(1),Math.min(getHeight()-dp(3),top+periods[1]*rh-dp(2)));bounds.add(rect);
                paint.setColor(colors[Math.floorMod(c.optString("course").hashCode(),colors.length)]);canvas.drawRoundRect(rect,dp(7),dp(7),paint);
                String value=c.optString("course")+"\n"+c.optString("location")+"\n"+c.optString("teacher");
                paint.setTextSize(dp(10));paint.setTypeface(Typeface.DEFAULT);paint.setColor(INK);float ty=rect.top+dp(14);float lineHeight=dp(14);
                outer:for(String segment:value.split("\n")){int offset=0;while(offset<segment.length()){int n=paint.breakText(segment,offset,segment.length(),true,Math.max(dp(5),rect.width()-dp(6)),null);if(n<=0)break;if(ty>rect.bottom-dp(3))break outer;canvas.drawText(segment,offset,offset+n,rect.left+dp(3),ty,paint);offset+=n;ty+=lineHeight;}}
            }
            if(visible.isEmpty())label(canvas,"本周没有固定课程安排",left+dp(25),top+dp(90),14,MUTED,false);
        }
        float downX,downY;
        @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==0){downX=event.getX();downY=event.getY();return true;}if(event.getActionMasked()==1&&Math.abs(event.getX()-downX)<dp(16)&&Math.abs(event.getY()-downY)<dp(16)){for(int i=0;i<bounds.size();i++)if(bounds.get(i).contains(event.getX(),event.getY())){JSONObject c=visible.get(i);new AlertDialog.Builder(MainActivity.this).setTitle(c.optString("course")).setMessage("教师："+c.optString("teacher")+"\n地点："+c.optString("location")+"\n节次："+c.optString("periods")+"\n周次："+c.optString("weeks")).setPositiveButton("关闭",null).show();performClick();return true;}}return true;}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
