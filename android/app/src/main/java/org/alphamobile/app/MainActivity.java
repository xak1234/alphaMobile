package org.alphamobile.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private Data data;
    private AppUpdates appUpdates;
    private LinearLayout updateSection;
    private boolean editingPairing;
    private final java.util.Set<String> expandedRows=new java.util.HashSet<>();
    private final Runnable updateChanged=this::drawUpdates;
    private Ui ui;
    private DeviceStatus deviceStatus;
    private LinearLayout root,body,nav,transcript,list;
    private ScrollView scroll;
    private TextView connection,voiceLabel,callbackLabel;
    private JSONObject pendingCallback;
    private boolean callbackStarting;
    private EditText composer;
    private Button availability;
    private Ui.Core aiCore;
    private final android.os.Handler linkHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean checkingLink, visible, messageSending;
    private final Runnable linkRefresh=()->{refresh();pollConnection(false);};
    private int page=-1;
    private TextView[] hubCounts,navCaptions;
    private String presentedIncoming="";
    private String selected="", signature="", transcriptSignature="", filter="", pendingAnswer="", taskSignature="";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refresh();}};
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle state){super.onCreate(state);data=new Data(this);appUpdates=AppUpdates.get(this);
        if(data.flag("receiver_recovery",false)){stopService(new Intent(this,CallService.class));state=null;}
        if(state!=null){page=state.getInt("page");selected=state.getString("selected","");presentedIncoming=state.getString("presentedIncoming","");java.util.ArrayList<String> opened=state.getStringArrayList("expanded_rows");if(opened!=null)expandedRows.addAll(opened);}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(updates,new IntentFilter(CallService.UPDATE),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(updates,new IntentFilter(CallService.UPDATE),getPackageName()+".INTERNAL",null);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::navigateBack);
        if(state!=null)try{String callback=state.getString("pending_callback","");if(!callback.isEmpty())pendingCallback=new JSONObject(callback);}catch(Exception ignored){}
        readIntent(getIntent());render();
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);readIntent(intent);render();}
    private void readIntent(Intent intent){if(intent.getBooleanExtra("messages",false)){page=1;selected="";}String id=intent.getStringExtra("call_id");if(id!=null&&!id.isEmpty()){selected=id;
        if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true);}
        if("answer".equals(intent.getStringExtra("action"))&&!data.flag("receiver_recovery",false))pendingAnswer=id;intent.removeExtra("action");}
    }
    @Override protected void onResume(){super.onResume();if(!pendingAnswer.isEmpty()){String id=pendingAnswer;pendingAnswer="";answer(id);}visible=true;if(page==1&&selected.isEmpty()&&!expandedRows.isEmpty())fillMessages();MessageAlerts.get(this).viewing(true,selected);appUpdates.listen(updateChanged);if(page==4&&selected.isEmpty())refreshSettings();else drawUpdates();refresh();pollConnection(true);if(pendingCallback!=null&&!callbackStarting)prepareCallback();}
    @Override protected void onPause(){visible=false;MessageAlerts.get(this).viewing(false,"");appUpdates.unlisten(updateChanged);linkHandler.removeCallbacks(linkRefresh);super.onPause();}
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putStringArrayList("expanded_rows",new java.util.ArrayList<>(expandedRows));state.putInt("page",page);state.putString("selected",selected);state.putString("presentedIncoming",presentedIncoming);if(pendingCallback!=null)state.putString("pending_callback",pendingCallback.toString());}
    private boolean dark(){String t=data.get("theme","dark");return "dark".equals(t)||"blue".equals(t)||("system".equals(t)&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    private void render(){
        if(isDestroyed()||isFinishing())return;
        JSONObject shown=data.call(selected);
        if(Build.VERSION.SDK_INT>=27){boolean voiceCall=shown!=null&&!"chat".equals(shown.optString("channel"))&&(Data.isIncoming(shown)||"active".equals(shown.optString("state")));setShowWhenLocked(voiceCall);setTurnScreenOn(Data.isIncoming(shown));}
        ui=new Ui(this,dark(),"blue".equals(data.get("theme","dark")));root=ui.column();if(page==-1&&selected.isEmpty())root.setBackground(ui.backdrop());else if(ui.blue)root.setBackground(ui.innerBackground());else root.setBackgroundColor(ui.bg);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));}else v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);root.requestApplyInsets();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(ui.bg);getWindow().setNavigationBarColor(ui.bg);
        if(Build.VERSION.SDK_INT>=30 && getWindow().getDecorView().getWindowInsetsController()!=null)getWindow().getDecorView().getWindowInsetsController().setSystemBarsAppearance(dark()?0:android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        deviceStatus=new DeviceStatus(this,ui);root.addView(deviceStatus,new LinearLayout.LayoutParams(-1,-2));
        body=ui.column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        updateSection=null;voiceLabel=null;callbackLabel=null;connection=null;transcript=null;composer=null;availability=null;aiCore=null;list=null;hubCounts=null;navCaptions=null;transcriptSignature="";
        MessageAlerts.get(this).viewing(visible,selected);
        if(!selected.isEmpty()&&data.call(selected)!=null){conversation(data.call(selected));return;}
        selected="";header();scroll=new ScrollView(this);scroll.setFillViewport(true);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=ui.column();content.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(20));scroll.addView(content);
        if(page==-1)dashboard(content);else if(page==0)history(content);else if(page==1)messages(content);else if(page==2)tasks(content);else if(page==3)agents(content);else settings(content);
        if(page>=0&&page<4)bottom();signature=snapshot();taskSignature=data.get("tasks","")+data.get("task_sync","");
    }
    private void header(){LinearLayout top=ui.row();top.setPadding(ui.dp(20),ui.dp(14),ui.dp(20),ui.dp(16));
        LinearLayout word=ui.column();TextView title=ui.text("alphaMobile",27,ui.text,true);title.setLetterSpacing(-0.035f);android.text.SpannableString brand=new android.text.SpannableString("alphaMobile");brand.setSpan(new android.text.style.ForegroundColorSpan(ui.accent),5,11,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);title.setText(brand);word.addView(title);TextView strap=ui.label("PRIVATE INTELLIGENCE",ui.sub);strap.setTextSize(8);word.addView(strap);word.setContentDescription("alphaMobile home");word.setFocusable(true);word.setOnClickListener(v->openPage(-1));top.addView(word,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout settings=new FrameLayout(this);if(page==-1)settings.setBackground(ui.panel(false));settings.setContentDescription(page==-1?"Settings":"Back to dashboard");settings.setFocusable(true);View glyph=ui.icon(page==-1?"Settings":"back",ui.accent);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(ui.dp(21),ui.dp(21),Gravity.CENTER);settings.addView(glyph,p);settings.setOnClickListener(v->openPage(page==-1?4:-1));top.addView(settings,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));body.addView(top);callbackNotice(body);
    }
    private void openPage(int index){page=index;filter="";selected="";render();if(page==1)fetchMessages(false);if(page==2)fetchTasks();if(page==3)fetchAgents();if(page==4)appUpdates.automaticCheck();}
    private void bottom(){nav=ui.row();nav.setPadding(ui.dp(10),ui.dp(12),ui.dp(10),ui.dp(10));nav.setBackgroundColor(ui.bg);ui.rule(root);
        String[] labels={"Calls","Messages","Tasks","Agents"};navCaptions=new TextView[4];
        for(int i=0;i<labels.length;i++){final int index=i;LinearLayout item=ui.column();item.setGravity(Gravity.CENTER);int colour=i==page?ui.accent:ui.sub;
            item.addView(ui.icon(labels[i],colour),new LinearLayout.LayoutParams(ui.dp(22),ui.dp(22)));ui.gap(item,5);TextView caption=ui.text(labels[i]+(i==1&&MessageState.count(data,null)>0?" · "+MessageState.count(data,null):""),10,colour,i==page);caption.setGravity(Gravity.CENTER);navCaptions[i]=caption;item.addView(caption);item.setContentDescription(labels[i]);item.setFocusable(true);item.setSelected(i==page);
            item.setOnClickListener(v->openPage(index));nav.addView(item,new LinearLayout.LayoutParams(0,ui.dp(50),1));}
        root.addView(nav);
    }
    private void dashboard(LinearLayout content){
        LinearLayout heading=ui.row();TextView section=ui.label("COMMAND CENTRE",ui.accent);heading.addView(section,new LinearLayout.LayoutParams(0,-2,1));heading.addView(ui.label("01 — 04",ui.sub));content.addView(heading);ui.gap(content,9);content.addView(ui.text("Your AI. Within reach.",24,ui.text,true));ui.gap(content,22);
        FrameLayout board=new FrameLayout(this);LinearLayout grid=ui.column();board.addView(grid,new FrameLayout.LayoutParams(-1,-1));hubCounts=new TextView[4];String[] labels={"Calls","Messages","Tasks","Agents"};String[] types={"VOICE","CONVERSATIONS","SCHEDULES","CONNECTIONS"};String[] captions={"History & redial","Every conversation","Schedules & results","Your AI network"};
        for(int row=0;row<2;row++){LinearLayout pair=ui.row();grid.addView(pair,new LinearLayout.LayoutParams(-1,0,1));for(int col=0;col<2;col++){final int index=row*2+col;LinearLayout tile=ui.column();tile.setGravity(Gravity.CENTER_HORIZONTAL);tile.setPadding(ui.dp(12),ui.dp(14),ui.dp(12),ui.dp(14));tile.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3359DEFF),ui.panel(true),null));tile.setFocusable(true);tile.setOnClickListener(v->openPage(index));tile.setContentDescription(labels[index]+". "+captions[index]);
                LinearLayout topline=ui.row();TextView category=ui.label(types[index],ui.sub);category.setTextSize(8);topline.addView(category,new LinearLayout.LayoutParams(0,-2,1));TextView number=ui.label("0"+(index+1),ui.accent);number.setTextSize(8);topline.addView(number);if(row==1){category.setText(col==0?"03 / SCHEDULES":"CONNECTIONS / 04");category.setGravity(col==0?Gravity.START:Gravity.END);number.setVisibility(View.GONE);}tile.addView(topline,new LinearLayout.LayoutParams(-1,-2));
                ui.gap(tile,4);tile.addView(ui.moduleArt(labels[index]),new LinearLayout.LayoutParams(ui.dp(80),ui.dp(70)));ui.gap(tile,4);TextView title=ui.text(labels[index],22,ui.text,true);title.setGravity(Gravity.CENTER);tile.addView(title);ui.gap(tile,4);TextView caption=ui.text(captions[index],11,ui.sub,false);caption.setGravity(Gravity.CENTER);tile.addView(caption);ui.gap(tile,11);hubCounts[index]=ui.label("",ui.accent);hubCounts[index].setTextSize(9);hubCounts[index].setGravity(Gravity.CENTER);tile.addView(hubCounts[index]);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);if(col==0)p.rightMargin=ui.dp(12);pair.addView(tile,p);
            }if(row==0)ui.gap(grid,12);}
        Ui.Core core=ui.core();aiCore=core;core.connected(data.aiConnected());core.setOnClickListener(v->openPage(4));board.addView(core,new FrameLayout.LayoutParams(ui.dp(128),ui.dp(128),Gravity.CENTER));float scale=Math.max(1f,getResources().getConfiguration().fontScale);content.addView(board,new LinearLayout.LayoutParams(-1,ui.dp(424*scale)));ui.gap(content,20);
        LinearLayout link=ui.card();LinearLayout linkTitle=ui.row();linkTitle.addView(ui.label("AGENT CONNECTION",ui.accent),new LinearLayout.LayoutParams(0,-2,1));linkTitle.addView(ui.icon("wave",ui.accent),new LinearLayout.LayoutParams(ui.dp(38),ui.dp(18)));link.addView(linkTitle);ui.gap(link,9);connection=ui.text(status(),13,ui.sub,false);link.addView(connection);ui.gap(link,14);availability=ui.button(!data.paired()?"Pair with my box":CallService.instance==null?"Become available":"Available · Go offline",true,()->{if(!data.paired())openPage(4);else toggleAvailability();});ui.availability(availability,CallService.instance!=null);link.addView(availability,new LinearLayout.LayoutParams(-1,ui.dp(48)));content.addView(link);
        LinearLayout latest=ui.row();latest.setPadding(ui.dp(2),ui.dp(5),ui.dp(2),ui.dp(12));TextView lastLabel=ui.label("LAST CONTACT",ui.sub);lastLabel.setTextSize(8);latest.addView(lastLabel);JSONObject last=data.calls().optJSONObject(0);TextView lastText=ui.text(last==null?"No conversations yet":last.optString("agent_name")+" · "+label(last.optString("state")).toLowerCase(Locale.UK),11,ui.sub,false);lastText.setGravity(Gravity.END);lastText.setPadding(ui.dp(12),0,0,0);latest.addView(lastText,new LinearLayout.LayoutParams(0,-2,1));latest.setOnClickListener(v->openPage(0));content.addView(latest);
        TextView footer=ui.label("VOICE  /  TEXT  /  AGENTS          α 0.1",ui.sub);footer.setTextSize(8);footer.setGravity(Gravity.CENTER);content.addView(footer);updateHub();
    }
    private JSONArray cachedArray(String key){try{return new JSONArray(data.get(key,"[]"));}catch(Exception e){return new JSONArray();}}
    private void updateHub(){if(navCaptions!=null){int unreadNav=MessageState.count(data,null);navCaptions[1].setText(unreadNav>0?"Messages · "+unreadNav:"Messages");}if(hubCounts==null)return;JSONArray calls=data.calls();int messages=0,missed=0,voiceCalls=0;for(int i=0;i<calls.length();i++){JSONObject c=calls.optJSONObject(i);if(c==null)continue;if(!"chat".equals(c.optString("channel")))voiceCalls++;JSONArray m=c.optJSONArray("messages");messages+=Math.max(c.optInt("message_count"),m==null?0:m.length());if("missed".equals(c.optString("state")))missed++;}
        hubCounts[0].setText(missed>0?missed+" MISSED":voiceCalls+" CALLS");int unread=MessageState.count(data,null);hubCounts[1].setText(unread>0?"● "+unread+" UNREAD":data.get("message_total",String.valueOf(data.messages().length()))+" MESSAGES");hubCounts[1].setTextColor(unread>0?ui.red:ui.accent);int count=cachedArray("tasks").length();hubCounts[2].setText(count==0?(data.flag("tasks_synced",false)?"NO TASKS":"AWAITING SYNC"):count+" TASK"+(count==1?"":"S"));count=cachedArray("agents").length();hubCounts[3].setText(count+" AGENT"+(count==1?"":"S"));
    }
    private JSONObject lastVoiceCall(){JSONArray calls=data.calls();for(int i=0;i<calls.length();i++){JSONObject c=calls.optJSONObject(i);if(c!=null&&!"chat".equals(c.optString("channel")))return c;}return null;}
    private void history(LinearLayout content){
        content.addView(ui.label(page==0?"01 / VOICE":"02 / CONVERSATIONS",ui.accent));ui.gap(content,12);
        LinearLayout title=ui.row();title.addView(ui.text(page==0?"Recent calls":"Conversations",22,ui.text,true),new LinearLayout.LayoutParams(0,-2,1));
        JSONObject recent=lastVoiceCall();if(recent!=null)title.addView(ui.action("Redial",()->callback(recent)));content.addView(title);ui.gap(content,14);
        EditText search=field("Search names, messages or topics",filter,false);search.setSingleLine(true);search.setTextSize(14);content.addView(search,new LinearLayout.LayoutParams(-1,ui.dp(48)));ui.gap(content,16);
        list=ui.column();content.addView(list);fillHistory();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){filter=s.toString();fillHistory();}public void afterTextChanged(Editable e){}});
    }
    private String status(){CallService service=CallService.instance;return !data.paired()?"Pair with my box using your keyword.":service==null?data.get("availability_error","").isEmpty()?"Paired · Offline until you become available":data.get("availability_error",""):service.status;}
    private void fillHistory(){if(page==1){fillMessages();return;}if(list==null)return;list.removeAllViews();int shown=0;
        java.util.ArrayList<JSONObject> calls=new java.util.ArrayList<>();JSONArray cached=data.calls();for(int i=0;i<cached.length();i++){JSONObject c=cached.optJSONObject(i);if(c!=null)calls.add(c);}calls.sort((a,b)->Double.compare(b.optDouble("created"),a.optDouble("created")));
        for(JSONObject call:calls){if("chat".equals(call.optString("channel"))||!matches(call))continue;
            compactEntry(list,"call:"+call.optString("id"),call.optString("agent_name"),call.optDouble("created"),subject(call.optString("reason"),"Agent call"),()->false,item->{
                entryHeading(item,call.optString("agent_name"),call.optDouble("created"),call.optString("reason"));String state=call.optString("state");item.addView(ui.text(label(state)+" · MCP",12,"missed".equals(state)||"failed".equals(state)?ui.red:ui.accent,false));
                item.addView(ui.action("Open call and transcript  ›",()->openConversation(call.optString("id"))));
                if(Data.isTerminal(call))item.addView(ui.action("Call back",()->callback(call)));
            });shown++;
        }
        if(shown==0){ui.gap(list,20);list.addView(ui.text(filter.isEmpty()?"No calls yet":"No matching calls",20,ui.text,true));ui.gap(list,8);list.addView(ui.text(filter.isEmpty()?"Calls from your connected agents will appear here.":"Try another name or topic.",14,ui.sub,false));}
    }
    private String subject(String... values){for(String value:values)if(value!=null&&!value.trim().isEmpty()&&!"null".equals(value))return value.trim().replaceAll("\\s+"," ");return "No subject";}
    private String compactTime(double seconds){return seconds>0?new SimpleDateFormat("dd/MM HH:mm",Locale.UK).format(new Date((long)(seconds*1000))):"—";}
    private void entryHeading(LinearLayout details,String agent,double timestamp,String title){details.addView(ui.text(agent+" · "+date(timestamp)+" · "+clock(timestamp),12,ui.sub,false));ui.gap(details,8);details.addView(ui.text(title,16,ui.text,true));ui.gap(details,10);}
    private void openConversation(String id){if(data.call(id)!=null){selected=id;render();fetchCall(id);return;}worker.execute(()->{try{data.saveCall(Api.call(data,"/api/calls/"+id,null));runOnUiThread(()->{selected=id;render();});}catch(Exception e){error(e);}});}
    /** One physical line by default, with full metadata in its accessible label and expansion. */
    private void compactEntry(LinearLayout parent,String key,String agent,double timestamp,String title,java.util.function.BooleanSupplier unread,java.util.function.Consumer<LinearLayout> populate){
        LinearLayout item=ui.column(),header=ui.row(),details=ui.column();parent.addView(item);ui.rule(parent);header.setTag("compact:"+key);header.setMinimumHeight(ui.dp(48));header.setGravity(Gravity.CENTER_VERTICAL);header.setFocusable(true);
        header.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2259DEFF),null,ui.shape(Color.WHITE,0)));
        TextView name=ui.text(agent,12,ui.text,true),when=ui.text(compactTime(timestamp),11,ui.sub,false),topic=ui.text(title,13,ui.text,false),arrow=ui.text("›",22,ui.accent,false);
        for(TextView text:new TextView[]{name,when,topic,arrow}){text.setSingleLine(true);text.setEllipsize(android.text.TextUtils.TruncateAt.END);text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        name.setPadding(0,0,ui.dp(8),0);when.setPadding(0,0,ui.dp(10),0);arrow.setGravity(Gravity.CENTER);
        header.addView(name,new LinearLayout.LayoutParams(0,-2,0.85f));header.addView(when,new LinearLayout.LayoutParams(-2,-2));header.addView(topic,new LinearLayout.LayoutParams(0,-2,1.15f));header.addView(arrow,new LinearLayout.LayoutParams(ui.dp(20),-2));item.addView(header);
        details.setTag("details:"+key);details.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(16));item.addView(details);
        Runnable draw=()->{boolean open=expandedRows.contains(key);details.removeAllViews();details.setVisibility(open?View.VISIBLE:View.GONE);if(open)populate.accept(details);boolean fresh=unread.getAsBoolean();name.setText((fresh?"● ":"")+agent);name.setTextColor(fresh?ui.red:ui.text);arrow.setRotation(open?90:0);header.setSelected(open);header.setContentDescription((open?"Collapse ":"Expand ")+(fresh?"unread, ":"")+agent+", "+date(timestamp)+", "+clock(timestamp)+", "+title);};
        header.setOnClickListener(v->{if(!expandedRows.remove(key))expandedRows.add(key);draw.run();MessageAlerts.get(this).refresh();updateHub();});draw.run();
    }
    private boolean matches(JSONObject value){return filter.isEmpty()||value.toString().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));}
    private String date(double seconds){return seconds>0?new SimpleDateFormat("EEE d MMM yyyy",Locale.UK).format(new Date((long)(seconds*1000))):"No date recorded";}
    private String clock(double seconds){return seconds>0?new SimpleDateFormat("HH:mm",Locale.UK).format(new Date((long)(seconds*1000))):"";}
    private void rowTitle(LinearLayout parent,String title,String trailing){LinearLayout row=ui.row();TextView name=ui.text(title,18,ui.text,true);name.setPadding(0,0,ui.dp(12),0);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));row.addView(ui.text(trailing,11,ui.sub,false));parent.addView(row);}
    private void preview(LinearLayout parent,String value,int size,int colour,int lines){TextView text=ui.text(value,size,colour,false);text.setMaxLines(lines);text.setEllipsize(android.text.TextUtils.TruncateAt.END);text.setLineSpacing(ui.dp(2),1);parent.addView(text);}
    private String scheduledTime(String value){try{return java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM · HH:mm z",Locale.UK));}catch(Exception error){return value;}}
    private String snapshot(){return data.get("calls","[]")+data.get("messages","[]")+data.get("pending_ends","{}")+data.get("message_read_state","{}");}
    private void messages(LinearLayout content){
        content.addView(ui.label("02 / MESSAGES",ui.accent));ui.gap(content,12);content.addView(ui.text("From your agents",24,ui.text,true));ui.gap(content,8);
        content.addView(ui.text("Answers and updates, saved beyond the call.",13,ui.sub,false));ui.gap(content,12);content.addView(ui.action("New message",this::newMessage));ui.gap(content,16);
        EditText search=field("Search answers or questions",filter,false);content.addView(search);ui.gap(content,16);
        list=ui.column();content.addView(list);fillMessages();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){filter=s.toString();fillMessages();}public void afterTextChanged(Editable e){}});
        content.addView(ui.action("Refresh messages",()->fetchMessages(false)));ui.gap(content,10);
        content.addView(ui.action("Load older messages",()->fetchMessages(true)));
    }
    private void newMessage(){if(!data.paired()){toast("Pair with your box first");return;}worker.execute(()->{try{
        JSONArray agents=Api.call(data,"/api/agents",null).getJSONArray("agents");String[] names=new String[agents.length()];for(int i=0;i<names.length;i++)names[i]=agents.getJSONObject(i).getString("name");
        runOnUiThread(()->{if(names.length==0){toast("No agents connected yet");return;}new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,dark()?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle("Message an agent").setItems(names,(dialog,which)->openChat(agents.optJSONObject(which).optString("id"))).show();});
    }catch(Exception e){error(e);}});}
    private void openChat(String agent){worker.execute(()->{try{JSONObject call=Api.call(data,"/api/chats",new JSONObject().put("agent_id",agent));data.saveCall(call);runOnUiThread(()->{selected=call.optString("id");page=1;render();});}catch(Exception e){error(e);}});}
    private void sendChat(String id){if(messageSending||composer==null)return;String text=composer.getText().toString().trim();if(text.isEmpty())return;
        messageSending=true;composer.setEnabled(false);if(voiceLabel!=null)voiceLabel.setText("Sending…");
        worker.execute(()->{try{JSONObject pending=new JSONObject(data.get("chat_pending_"+id,"{}").isEmpty()?"{}":data.get("chat_pending_"+id,"{}"));
            if(!text.equals(pending.optString("text")))pending=new JSONObject().put("id",UUID.randomUUID().toString()).put("role","user").put("text",text);
            data.put("chat_pending_"+id,pending.toString());JSONObject call=Api.call(data,"/api/calls/"+id+"/turn",pending);data.saveCall(call);data.put("chat_pending_"+id,"");
            runOnUiThread(()->{messageSending=false;if(composer!=null){composer.setEnabled(true);composer.setText("");}refresh();});
        }catch(Exception e){runOnUiThread(()->{messageSending=false;if(composer!=null)composer.setEnabled(true);if(voiceLabel!=null)voiceLabel.setText("Not confirmed · Tap Send to retry");});error(e);}});
    }
    private void fillMessages(){if(list==null)return;list.removeAllViews();int shown=0,threadsShown=0,savedShown=0;
        JSONArray threads=data.calls();for(int i=0;i<threads.length();i++){JSONObject c=threads.optJSONObject(i);if(c==null||!"chat".equals(c.optString("channel"))||!matches(c))continue;
            if(threadsShown++==0){ui.gap(list,8);list.addView(ui.label("CONVERSATIONS",ui.sub));ui.gap(list,6);}
            JSONArray messages=c.optJSONArray("messages");String topic=c.optString("reason");if(messages!=null)for(int j=messages.length()-1;j>=0;j--){JSONObject m=messages.optJSONObject(j);if(m!=null&&"user".equals(m.optString("role"))){topic=m.optString("text");break;}}
            final String title=subject(topic,"Conversation");double updated=c.optDouble("updated",c.optDouble("created"));
            compactEntry(list,"chat:"+c.optString("id"),c.optString("agent_name"),updated,title,()->MessageState.count(data,c.optString("id"))>0,item->{
                entryHeading(item,c.optString("agent_name"),updated,title);int count=messages==null?0:messages.length();
                if(count==0)item.addView(ui.text("Type your first message",14,ui.sub,false));
                for(int j=0;j<count;j++){JSONObject m=messages.optJSONObject(j);if(m==null)continue;String role=m.optString("role");ui.gap(item,8);item.addView(ui.text(("user".equals(role)?"You":"agent".equals(role)?c.optString("agent_name"):"Status")+" · "+clock(m.optDouble("created")),11,ui.sub,true));TextView text=ui.text(m.optString("text"),14,ui.text,false);text.setTextIsSelectable(true);item.addView(text);
                    if(visible&&"agent".equals(role)){JSONArray saved=data.messages();for(int k=0;k<saved.length();k++){JSONObject reply=saved.optJSONObject(k);if(reply!=null&&m.optString("id").equals(reply.optString("id"))&&MessageState.version(m).equals(MessageState.version(reply)))MessageState.read(data,reply);}}
                }
                if(c.optBoolean("awaiting_agent")){ui.gap(item,8);item.addView(ui.text("Waiting for "+c.optString("agent_name"),12,ui.accent,false));}
                item.addView(ui.action("Open conversation / Reply  ›",()->openConversation(c.optString("id"))));
            });shown++;
        }
        JSONArray items=data.messages();for(int i=0;i<items.length();i++){JSONObject m=items.optJSONObject(i);if(m==null||!matches(m))continue;JSONObject thread=data.call(m.optString("call_id"));if(thread!=null&&"chat".equals(thread.optString("channel")))continue;
            if(savedShown++==0){ui.gap(list,20);list.addView(ui.label("SAVED REPLIES",ui.sub));ui.gap(list,6);}
            compactEntry(list,"message:"+m.optString("id"),m.optString("agent_name"),m.optDouble("created"),subject(m.optString("question"),m.optString("reason"),m.optString("text")),()->MessageState.unread(data,m),item->{
                entryHeading(item,m.optString("agent_name"),m.optDouble("created"),subject(m.optString("question"),m.optString("reason")));TextView full=ui.text(m.optString("text"),15,ui.text,false);full.setTextIsSelectable(true);item.addView(full);
                if(m.optInt("is_final",1)!=1){ui.gap(item,8);item.addView(ui.text(m.optInt("is_stream")==1?"Reply arriving…":"Progress update",12,ui.accent,false));}
                item.addView(ui.action("Open conversation  ›",()->openConversation(m.optString("call_id"))));if(visible)MessageState.read(data,m);
            });shown++;
        }
        MessageAlerts.get(this).refresh();updateHub();
        if(shown==0){ui.gap(list,20);list.addView(ui.text(filter.isEmpty()?"Your messages, in one place":"No matching messages",20,ui.text,true));ui.gap(list,8);list.addView(ui.text(filter.isEmpty()?"Start a conversation, or find saved answers from your calls here.":"Try a different question or topic.",14,ui.sub,false));}
    }
    private void fetchMessages(boolean older){if(!data.paired())return;worker.execute(()->{try{
        int offset=older?Integer.parseInt(data.get("message_offset","0")):0;
        JSONObject result=Api.call(data,"/api/messages?offset="+offset,null);data.mergeMessages(result.getJSONArray("messages"));data.put("message_total",String.valueOf(result.optInt("total")));
        data.put("message_offset",String.valueOf(result.optInt("next_offset")));data.flag("messages_more",result.optBoolean("has_more"),true);
        runOnUiThread(()->{if(page==1)fillMessages();updateHub();if(older&&result.optJSONArray("messages").length()==0)toast("All messages loaded");});
    }catch(Exception e){error(e);}});}
    private TextView avatar(String name){String initial=name.trim().isEmpty()?"α":name.trim().substring(0,1).toUpperCase(Locale.ROOT);TextView v=ui.text(initial,20,ui.accent,true);v.setGravity(Gravity.CENTER);v.setBackground(ui.shape(dark()?Color.parseColor("#0D354A"):Color.parseColor("#D9F3FC"),18));return v;}
    private String time(double seconds){return new SimpleDateFormat("dd MMM\nHH:mm",Locale.UK).format(new Date((long)(seconds*1000)));}
    private String label(String state){switch(state){case"ringing":return"INCOMING";case"active":return"IN CONVERSATION";case"completed":return"COMPLETED";case"declined":return"DECLINED";case"cancelled":return"CANCELLED";case"failed":return"INTERRUPTED";default:return"MISSED";}}
    private void agents(LinearLayout content){content.addView(ui.text("Your agents",26,ui.text,true));ui.gap(content,8);content.addView(ui.text(AppUpdates.boxName(data.base()),13,ui.sub,false));ui.gap(content,22);
        JSONArray agents=cachedArray("agents");if(agents.length()==0){content.addView(ui.text("No agents connected yet",20,ui.text,true));ui.gap(content,8);content.addView(ui.text("Authorised MCP agents appear here after you pair your box.",14,ui.sub,false));}
        for(int i=0;i<agents.length();i++){JSONObject agent=agents.optJSONObject(i);if(agent==null)continue;LinearLayout item=ui.entry(content);rowTitle(item,agent.optString("name"),"MCP");ui.gap(item,8);
            item.addView(ui.text(agent.optBoolean("online")?"Connected":"Offline",13,agent.optBoolean("online")?ui.accent:ui.sub,false));double seen=agent.optDouble("seen");if(seen>0){ui.gap(item,5);item.addView(ui.text("Last seen · "+date(seen)+" · "+clock(seen),12,ui.sub,false));}
            item.addView(ui.action("Request a call",()->requestCallback(agent.optString("id"),null)));
        }
        content.addView(ui.action("Refresh agents",this::fetchAgents));String callback=data.get("callback_status","");if(!callback.isEmpty()){ui.gap(content,12);content.addView(ui.text(callback,13,ui.accent,false));}
    }
    private void tasks(LinearLayout content){
        content.addView(ui.text("Scheduled tasks",26,ui.text,true));ui.gap(content,8);
        content.addView(ui.text("Schedules and results from Claude and your connected agents.",13,ui.sub,false));ui.gap(content,12);JSONArray sync=cachedArray("task_sync");for(int i=0;i<sync.length();i++){JSONObject r=sync.optJSONObject(i);if(r==null)continue;String state=r.optString("state");content.addView(ui.text(r.optString("agent_name")+" · "+("pending".equals(state)?"Waiting for automatic task sync":r.optString("note")),12,"unavailable".equals(state)?ui.red:ui.accent,false));ui.gap(content,6);}ui.gap(content,14);
        JSONArray tasks;try{tasks=new JSONArray(data.get("tasks","[]"));}catch(Exception e){tasks=new JSONArray();}
        if(tasks.length()==0){LinearLayout c=ui.section();c.addView(ui.text("Your agents' work, in view",20,ui.text,true));ui.gap(c,10);c.addView(ui.text("Your connected agents will share their schedules and results here. Connect an agent that can report its schedules.",14,ui.sub,false));content.addView(c);}
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;JSONArray runs=task.optJSONArray("results");JSONObject latest=runs==null?null:runs.optJSONObject(0);double updated=latest==null?task.optDouble("updated"):latest.optDouble("received",task.optDouble("updated"));
            if(latest!=null)try{updated=java.time.OffsetDateTime.parse(latest.optString("finished_at")).toInstant().toEpochMilli()/1000.0;}catch(java.time.format.DateTimeParseException ignored){}
            final double timestamp=updated;
            compactEntry(content,"task:"+task.optString("id"),task.optString("agent_name"),timestamp,subject(task.optString("title")),()->false,c->{
                entryHeading(c,task.optString("agent_name"),timestamp,task.optString("title"));c.addView(ui.text((task.optBoolean("enabled",task.optInt("enabled",1)==1)?"SCHEDULED":"PAUSED")+" · "+task.optString("schedule")+" · "+task.optString("timezone"),12,ui.sub,false));
                if(!task.isNull("next_run")){ui.gap(c,6);c.addView(ui.text("Next · "+scheduledTime(task.optString("next_run")),12,ui.accent,false));}
                if(latest!=null){ui.gap(c,12);c.addView(ui.label("LATEST RUN · "+latest.optString("status").toUpperCase(Locale.UK),"failed".equals(latest.optString("status"))?ui.red:ui.accent));ui.gap(c,7);c.addView(ui.text(latest.optString("summary"),16,ui.text,true));if(!latest.optString("output").isEmpty()){ui.gap(c,7);TextView output=ui.text(latest.optString("output"),14,ui.sub,false);output.setTextIsSelectable(true);c.addView(output);}ui.gap(c,8);c.addView(ui.text(runTime(latest),11,ui.sub,false));}
                else{ui.gap(c,10);c.addView(ui.text("No run results reported yet",13,ui.sub,false));}
                ui.gap(c,10);c.addView(ui.text("Schedule updated · "+date(task.optDouble("updated"))+" · "+clock(task.optDouble("updated")),11,ui.sub,false));
                c.addView(ui.action("Full results and run history  ›",()->worker.execute(()->{try{JSONObject detail=Api.call(data,"/api/tasks/"+task.optString("id"),null);runOnUiThread(()->showTask(detail));}catch(Exception e){error(e);}})));
            });
        }
        content.addView(ui.action("Refresh tasks",this::fetchTasks));
    }
    private String runTime(JSONObject run){String finished=run.optString("finished_at","");if(!finished.isEmpty()&&!"null".equals(finished))return "Finished · "+scheduledTime(finished);String started=run.optString("started_at","");return "Updated · "+date(run.optDouble("received"))+" · "+clock(run.optDouble("received"))+(!started.isEmpty()&&!"null".equals(started)?" · Started "+scheduledTime(started):"");}
    private void showTask(JSONObject task){LinearLayout details=ui.column();details.setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(20));details.setBackgroundColor(ui.bg);TextView description=ui.text(task.optString("description"),14,ui.sub,false);details.addView(description);JSONArray runs=task.optJSONArray("results");
        if(runs!=null)for(int i=0;i<runs.length();i++){JSONObject run=runs.optJSONObject(i);if(run==null)continue;ui.gap(details,20);details.addView(ui.text(run.optString("status").toUpperCase(Locale.UK),11,"failed".equals(run.optString("status"))?ui.red:ui.accent,false));ui.gap(details,8);details.addView(ui.text(run.optString("summary"),16,ui.text,true));ui.gap(details,6);TextView output=ui.text(run.optString("output"),14,ui.sub,false);output.setTextIsSelectable(true);details.addView(output);ui.gap(details,8);details.addView(ui.text(runTime(run),11,ui.sub,false));}
        ScrollView container=new ScrollView(this);container.addView(details);new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,dark()?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle(task.optString("title")).setView(container).setPositiveButton("Close",null).show();
    }
    private void fetchTasks(){if(!data.paired())return;worker.execute(()->{try{JSONObject r=Api.call(data,"/api/tasks",null);data.snapshot(r);runOnUiThread(()->{if(page==2)refreshSettings();else if(page==-1)updateHub();});}catch(Exception e){error(e);}});}
    private void fetchAgents(){if(!data.paired()){toast("Pair your box in Settings first");return;}worker.execute(()->{try{JSONObject r=Api.call(data,"/api/agents",null);data.put("agents",r.getJSONArray("agents").toString());runOnUiThread(()->{if(page==3)render();else if(page==-1)updateHub();});}catch(Exception e){error(e);}});}
    private EditText field(String hint,String value,boolean secret){EditText input=new EditText(this);input.setTextColor(ui.text);input.setHintTextColor(ui.sub);input.setTextSize(15);input.setPadding(ui.dp(14),ui.dp(10),ui.dp(14),ui.dp(10));input.setBackground(ui.shape(ui.line,13));input.setHint(hint);input.setText(value);input.setSingleLine(true);if(secret)input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return input;}
    private void settings(LinearLayout content){content.addView(ui.text("Settings",26,ui.text,true));ui.gap(content,20);
        LinearLayout pairing=ui.section();pairing.addView(ui.text("Your box",18,ui.text,true));ui.gap(pairing,6);pairing.addView(ui.text(data.paired()?"Paired · credentials managed automatically":"Pair privately over Tailscale",12,ui.sub,false));ui.gap(pairing,14);
        pairing.addView(ui.text(AppUpdates.boxName(data.base()),13,ui.accent,false));
        if(data.paired()&&!editingPairing){pairing.addView(ui.action("Change pairing",()->{editingPairing=true;render();}));}
        else {
        EditText address=field("Box HTTPS address",data.base(),false);address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);pairing.addView(address);ui.gap(pairing,10);
        EditText keyword=field("Pairing keyword","",true);pairing.addView(keyword);ui.gap(pairing,14);
        Button pair=ui.button(data.paired()?"Pair again":"Pair with my box",true,()->{String base=address.getText().toString().trim();String word=keyword.getText().toString().trim();
            if(!base.startsWith("https://")&&!base.startsWith("http://127.0.0.1:")){toast("Use your box's HTTPS address");return;}
            if(word.length()<3){toast("Enter the pairing keyword");return;}
            worker.execute(()->{try{JSONObject r=Api.request(base,"/api/pair","",new JSONObject().put("keyword",word).put("name",Build.MANUFACTURER+" "+Build.MODEL),20000);
                data.token(r.getString("token"));data.put("device_id",r.getString("device_id"));data.put("backend",base);runOnUiThread(()->{keyword.setText("");toast("Paired with your box");editingPairing=false;fetchAgents();enable();render();});
            }catch(Exception e){error(e);}});
        });pairing.addView(pair);if(data.paired())pairing.addView(ui.action("Cancel",()->{editingPairing=false;render();}));}
        content.addView(pairing);ui.rule(content);
        updateSection=ui.section();content.addView(updateSection);drawUpdates();ui.rule(content);
        LinearLayout appearance=ui.section();appearance.addView(ui.text("Appearance",18,ui.text,true));ui.gap(appearance,6);appearance.addView(ui.text("Theme · Select one option",13,ui.sub,false));
        android.widget.RadioGroup themes=new android.widget.RadioGroup(this);String[] values={"dark","blue","light","system"},names={"Holographic · Dark","Midnight blue · Shaded navy","Ice · Light","Follow Android system"};
        for(int i=0;i<values.length;i++){android.widget.RadioButton option=new android.widget.RadioButton(this);option.setId(View.generateViewId());option.setTag(values[i]);option.setText(names[i]);option.setTextColor(ui.text);option.setTextSize(14);option.setMinHeight(ui.dp(48));option.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));themes.addView(option);if(values[i].equals(data.get("theme","dark")))themes.check(option.getId());}
        themes.setOnCheckedChangeListener((group,id)->{View option=group.findViewById(id);if(option!=null){data.put("theme",String.valueOf(option.getTag()));refreshSettings();}});appearance.addView(themes);content.addView(appearance);ui.rule(content);
        LinearLayout calls=ui.section();calls.addView(ui.text("Calls & notifications",18,ui.text,true));ui.gap(calls,10);
        calls.addView(settingToggle("Quiet mode","Silence incoming call alerts and message pings. Unread markers remain visible.","quiet",false,()->{MessageAlerts.get(this).refresh();refreshSettings();}));
        NotificationManager nm=getSystemService(NotificationManager.class);boolean notifications=nm.areNotificationsEnabled()&&(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
        permissionSetting(calls,"App notifications",notifications,"Needed for background call and message alerts.",()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
        android.app.NotificationChannel callChannel=nm.getNotificationChannel("incoming");boolean callSound=notifications&&(callChannel==null||(callChannel.getImportance()>=NotificationManager.IMPORTANCE_DEFAULT&&callChannel.getSound()!=null));
        permissionSetting(calls,"Incoming call sound",callSound&&!data.flag("quiet",false),data.flag("quiet",false)?"Silenced by Quiet mode.":"Ringtone and vibration are controlled by Android.",()->startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,"incoming")));
        android.app.NotificationChannel messageChannel=nm.getNotificationChannel("messages");boolean messageSound=notifications&&messageChannel!=null&&messageChannel.getImportance()>=NotificationManager.IMPORTANCE_DEFAULT&&messageChannel.getSound()!=null;
        permissionSetting(calls,"Unread message pings",messageSound&&!data.flag("quiet",false),"Repeat every 30 seconds until opened. Pings pause during calls. Become available for alerts while the app is closed.",()->startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,"messages")));
        if(Build.VERSION.SDK_INT>=34)permissionSetting(calls,"Full-screen incoming calls",nm.canUseFullScreenIntent(),"Allow the ringing screen over your lock screen.",()->startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName()))));
        content.addView(calls);ui.rule(content);
        LinearLayout voice=ui.section();voice.addView(ui.text("Voice",18,ui.text,true));
        permissionSetting(voice,"Microphone permission",checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,"Used only after answering a voice call. Typing works without it.",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        voice.addView(ui.text("Speech recognition stays on this phone. Questions go to your agent; alphaMobile waits for their answer.",13,ui.sub,false));voice.addView(ui.action("Choose Android voice and speech speed  ›",()->{try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}}));content.addView(voice);ui.rule(content);
        LinearLayout power=ui.section();power.addView(ui.text("Background connection",18,ui.text,true));ui.gap(power,10);boolean exempt=getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        permissionSetting(power,"Battery optimisation exemption",exempt,"For reliable background alerts, allow unrestricted battery use in Android. Keep Tailscale connected.",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));content.addView(power);ui.rule(content);
        LinearLayout about=ui.section();about.addView(ui.text("alphaMobile  "+BuildConfig.VERSION_NAME,17,ui.text,true));ui.gap(about,8);about.addView(ui.text("Independent voice communications for AI agents.\nMIT-licensed project · Grant Wilson\nAndroid 8+ · ARM64 / x86_64",12,ui.sub,false));ui.gap(about,12);about.addView(ui.action("Open-source licences",this::licences));ui.gap(about,10);about.addView(ui.text("Crash diagnostics go only to your paired box. Reports contain technical errors, not conversations or audio.",12,ui.sub,false));content.addView(about);
    }
    private void refreshSettings(){int y=scroll==null?0:scroll.getScrollY();render();if(scroll!=null)scroll.post(()->scroll.scrollTo(0,y));}
    private LinearLayout settingToggle(String name,String description,String key,boolean fallback,Runnable changed){LinearLayout group=ui.column();ui.gap(group,8);android.widget.Switch toggle=new android.widget.Switch(this);toggle.setText(name);toggle.setTextColor(ui.text);toggle.setTextSize(15);toggle.setMinHeight(ui.dp(48));toggle.setShowText(true);toggle.setTextOn("ON");toggle.setTextOff("OFF");toggle.setChecked(data.flag(key,fallback));toggle.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ui.accent,ui.sub}));group.addView(toggle);
        TextView state=ui.text(toggle.isChecked()?"Enabled":"Disabled",12,toggle.isChecked()?ui.accent:ui.sub,true);group.addView(state);ui.gap(group,5);group.addView(ui.text(description,12,ui.sub,false));ui.gap(group,12);toggle.setOnCheckedChangeListener((button,checked)->{data.flag(key,checked,true);state.setText(checked?"Enabled":"Disabled");state.setTextColor(checked?ui.accent:ui.sub);changed.run();});return group;}
    private void permissionSetting(LinearLayout parent,String name,boolean enabled,String description,Runnable manage){ui.gap(parent,14);rowTitle(parent,name,enabled?"Enabled":"Disabled");ui.gap(parent,6);parent.addView(ui.text(description,12,ui.sub,false));parent.addView(ui.action("Android settings  ›",manage));}
    private void drawUpdates(){if(updateSection==null||isDestroyed())return;updateSection.removeAllViews();updateSection.addView(ui.text("App updates",18,ui.text,true));ui.gap(updateSection,8);updateSection.addView(ui.text("Installed · "+BuildConfig.VERSION_NAME,13,ui.sub,false));ui.gap(updateSection,8);updateSection.addView(ui.text(appUpdates.status,14,ui.accent,false));
        JSONObject release=appUpdates.release;if(release!=null){ui.gap(updateSection,8);updateSection.addView(ui.text(String.format(Locale.UK,"%s · %.1f MB · %s",release.optString("version_name"),release.optLong("size")/1048576.0,date(release.optDouble("published_at"))),12,ui.sub,false));String notes=release.optString("release_notes");if(!notes.isEmpty()){ui.gap(updateSection,10);updateSection.addView(ui.text(notes,13,ui.text,false));}}
        if(!appUpdates.busy){updateSection.addView(ui.button("Check for updates",false,appUpdates::check));if(release!=null){ui.gap(updateSection,8);updateSection.addView(ui.button(appUpdates.ready==null?"Download update":"Install update",true,()->{if(appUpdates.ready==null)appUpdates.download();else installUpdate();}));}}
        else if(appUpdates.downloading)updateSection.addView(ui.action("Cancel download",appUpdates::cancel));
        updateSection.addView(settingToggle("Automatic update checks","Check when connected. Downloads and installation remain your choice.","update_auto",true,()->{drawUpdates();if(data.flag("update_auto",true))appUpdates.automaticCheck();}));
        permissionSetting(updateSection,"Install app updates",getPackageManager().canRequestPackageInstalls(),"Android asks you to confirm each installation.",()->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))));
        updateSection.addView(ui.text("Downloads come from your paired box. Android will ask you to confirm installation. Your pairing and history are kept.",12,ui.sub,false));
    }
    private boolean canInstallNow(){CallService service=CallService.instance;if(data.incoming()!=null||(service!=null&&!service.activeId().isEmpty())){toast("Finish your call before installing the update");return false;}return true;}
    private void installUpdate(){if(!canInstallNow())return;if(!getPackageManager().canRequestPackageInstalls()){try{startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),301);}catch(Exception e){error(e);}return;}
        appUpdates.prepareInstall(intent->{if(isDestroyed()||!visible||!canInstallNow())return;try{startActivityForResult(intent,302);}catch(Exception e){error(e);}});
    }
    private void licences(){try{String[] files=getAssets().list("notices");if(files==null||files.length==0){toast("No licence files found");return;}java.util.Arrays.sort(files);new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,dark()?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle("Open-source licences").setItems(files,(dialog,index)->{try(java.io.InputStream in=getAssets().open("notices/"+files[index]);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);TextView text=ui.text(out.toString(java.nio.charset.StandardCharsets.UTF_8.name()),12,ui.text,false);text.setTextIsSelectable(true);text.setPadding(ui.dp(18),ui.dp(12),ui.dp(18),ui.dp(18));ScrollView view=new ScrollView(this);view.setBackgroundColor(ui.bg);view.addView(text);new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,dark()?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle(files[index]).setView(view).setPositiveButton("Close",null).show();}catch(Exception e){error(e);}}).setNegativeButton("Close",null).show();}catch(Exception e){error(e);}}
    private void conversation(JSONObject call){String state=call.optString("state");boolean ringing="ringing".equals(state),active="active".equals(state),chat="chat".equals(call.optString("channel"));
        LinearLayout top=ui.row();top.setPadding(ui.dp(22),ui.dp(18),ui.dp(22),ui.dp(16));View back=ui.icon("back",ui.text);top.addView(back,new LinearLayout.LayoutParams(ui.dp(26),ui.dp(26)));back.setOnClickListener(v->{selected="";render();});
        LinearLayout names=ui.column();names.setPadding(ui.dp(16),0,0,0);names.addView(ui.text(call.optString("agent_name"),20,ui.text,true));names.addView(ui.text(ringing?"Incoming agent call":active?(chat?"Text conversation":"Private conversation"):label(state),11,ui.sub,false));top.addView(names,new LinearLayout.LayoutParams(0,-2,1));top.addView(ui.label("MCP",ui.accent));body.addView(top);
        if(ringing){incoming(call);return;}
        TextView reason=ui.text(call.optString("reason"),13,ui.sub,false);reason.setPadding(ui.dp(24),0,ui.dp(24),ui.dp(14));body.addView(reason);
        scroll=new ScrollView(this);scroll.setFillViewport(true);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));transcript=ui.column();transcript.setPadding(ui.dp(20),ui.dp(4),ui.dp(20),ui.dp(20));scroll.addView(transcript);fillTranscript(call);
        if(active){
            LinearLayout controls=ui.column();controls.setPadding(ui.dp(18),ui.dp(14),ui.dp(18),ui.dp(16));controls.setBackgroundColor(ui.card);
            LinearLayout voiceRow=ui.row();voiceRow.addView(ui.icon("wave",ui.accent),new LinearLayout.LayoutParams(ui.dp(25),ui.dp(25)));voiceLabel=ui.text(chat?(call.optBoolean("awaiting_agent")?"Waiting for "+call.optString("agent_name")+"…":"Type a message to "+call.optString("agent_name")):CallService.instance==null?"Open availability to reconnect":CallService.instance.voiceStatus,12,ui.accent,false);voiceLabel.setPadding(ui.dp(10),0,0,0);voiceRow.addView(voiceLabel,new LinearLayout.LayoutParams(0,-2,1));controls.addView(voiceRow);ui.gap(controls,12);
            LinearLayout compose=ui.row();composer=field("Message your agent…","",false);composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setSingleLine(false);composer.setMaxLines(3);composer.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));Button send=ui.button("Send",true,()->{if(chat){sendChat(call.optString("id"));return;}if(CallService.instance!=null){String value=composer.getText().toString();if(!CallService.instance.isBusy()){CallService.instance.send(value);composer.setText("");}else toast("Wait for the current reply, or end the call");}});LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ui.dp(76),ui.dp(48));sp.leftMargin=ui.dp(8);compose.addView(send,sp);controls.addView(compose);ui.gap(controls,12);
            LinearLayout buttons=ui.row();if(!chat){addSmall(buttons,"Speak",()->{if(CallService.instance==null||CallService.instance.activeId().isEmpty())answer(selected);else CallService.instance.interruptAndListen();});addSmall(buttons,"Mute",()->{if(CallService.instance!=null)CallService.instance.toggleMute();});addSmall(buttons,"More",()->new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,dark()?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert)).setItems(new String[]{"Repeat last reply","Retry saved message","Speaker","Earpiece"},(d,w)->{CallService s=CallService.instance;if(s==null)return;if(w==0)s.repeat();else if(w==1)s.retry();else s.speaker(w==2);}).show());Button end=ui.button("End",false,()->endCall(call.optString("id")));end.setTextColor(ui.red);buttons.addView(end,new LinearLayout.LayoutParams(0,ui.dp(46),1));controls.addView(buttons);}body.addView(controls);
        }else{LinearLayout footer=ui.column();footer.setPadding(ui.dp(22),ui.dp(12),ui.dp(22),ui.dp(18));if(data.hasPendingEnd(call.optString("id")))footer.addView(ui.text("Ended on this phone · Will sync with your box",12,ui.sub,false));callbackNotice(footer);footer.addView(ui.button("Call back",true,()->callback(call)),new LinearLayout.LayoutParams(-1,ui.dp(52)));body.addView(footer);}
        signature=snapshot();
    }
    private void addSmall(LinearLayout row,String label,Runnable run){Button b=ui.button(label,false,run);b.setTextSize(12);b.setPadding(0,0,0,0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ui.dp(46),1);p.rightMargin=ui.dp(6);row.addView(b,p);}
    private void fillTranscript(JSONObject call){if(transcript==null)return;JSONArray messages=call.optJSONArray("messages");String current=messages==null?"[]":messages.toString();if(current.equals(transcriptSignature))return;transcriptSignature=current;transcript.removeAllViews();
        TextView banner=ui.text("DIRECT AGENT REPLIES  ·  SAVED TO MESSAGES",9,ui.sub,true);banner.setGravity(Gravity.CENTER);banner.setLetterSpacing(0.08f);transcript.addView(banner);ui.gap(transcript,24);
        if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null)continue;boolean user="user".equals(m.optString("role"));LinearLayout bubble=ui.section();
            String who=user?"YOU":"agent".equals(m.optString("role"))?call.optString("agent_name").toUpperCase(Locale.UK):"status".equals(m.optString("role"))?"ALPHAMOBILE · CHECKING":"ALPHAMOBILE";
            bubble.addView(ui.text(who,9,user?ui.accent:ui.sub,true));ui.gap(bubble,7);TextView message=ui.text(m.optString("text"),16,ui.text,false);message.setTextIsSelectable(true);message.setLineSpacing(ui.dp(3),1);bubble.addView(message);
            if(m.optDouble("created")>0){ui.gap(bubble,8);bubble.addView(ui.text(date(m.optDouble("created"))+" · "+clock(m.optDouble("created")),10,ui.sub,false));}
            transcript.addView(bubble);ui.rule(transcript);
        }
        if(scroll!=null)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void incoming(JSONObject call){LinearLayout screen=ui.column();screen.setGravity(Gravity.CENTER);screen.setPadding(ui.dp(32),ui.dp(30),ui.dp(32),ui.dp(30));body.addView(screen,new LinearLayout.LayoutParams(-1,0,1));screen.addView(ui.pill("INCOMING AGENT CALL",ui.accent));ui.gap(screen,48);screen.addView(avatar(call.optString("agent_name")),new LinearLayout.LayoutParams(ui.dp(100),ui.dp(100)));ui.gap(screen,24);TextView name=ui.text(call.optString("agent_name"),30,ui.text,true);name.setGravity(Gravity.CENTER);screen.addView(name);ui.gap(screen,14);TextView reason=ui.text(call.optString("reason"),16,ui.sub,false);reason.setGravity(Gravity.CENTER);screen.addView(reason);ui.gap(screen,24);screen.addView(ui.text("Agent voice · Private connection",12,ui.accent,false));
        LinearLayout footer=ui.row();footer.setPadding(ui.dp(22),ui.dp(20),ui.dp(22),ui.dp(32));Button decline=ui.button("Decline",false,()->{if(CallService.instance!=null)CallService.instance.change(call.optString("id"),"declined");});decline.setTextColor(ui.red);footer.addView(decline,new LinearLayout.LayoutParams(0,ui.dp(58),1));View gap=new View(this);footer.addView(gap,new LinearLayout.LayoutParams(ui.dp(14),1));footer.addView(ui.button("Answer",true,()->answer(call.optString("id"))),new LinearLayout.LayoutParams(0,ui.dp(58),1));body.addView(footer);signature=snapshot();}
    private void callback(JSONObject call){if(call!=null)requestCallback(call.optString("agent_id"),call.optString("id"));}
    private void requestCallback(String agent,String previous){
        if(!data.paired()){toast("Pair first");return;}
        if(pendingCallback!=null){toast("Preparing your callback…");return;}
        try{pendingCallback=new JSONObject().put("agent_id",agent).put("request_key",UUID.randomUUID().toString());if(previous!=null)pendingCallback.put("previous_call_id",previous);}
        catch(Exception error){error(error);return;}
        callbackProgress("Preparing callback · Enabling incoming calls…");prepareCallback();
    }
    private void callbackNotice(LinearLayout parent){
        String text=data.get("callback_status","");if(text.isEmpty())return;
        callbackLabel=ui.text(text,13,ui.accent,false);callbackLabel.setPadding(ui.dp(18),ui.dp(8),ui.dp(18),ui.dp(8));parent.addView(callbackLabel);
    }
    private void callbackProgress(String text){data.put("callback_status",text);if(callbackLabel!=null)callbackLabel.setText(text);else render();}
    private void prepareCallback(){
        if(pendingCallback==null||callbackStarting)return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},104);return;}
        if(!getSystemService(NotificationManager.class).areNotificationsEnabled()){pendingCallback=null;callbackProgress("Enable alphaMobile notifications before requesting a callback.");return;}
        callbackStarting=true;
        if(CallService.instance==null&&!startReceiver(new Intent(this,CallService.class))){callbackStarting=false;pendingCallback=null;callbackProgress("Could not enable incoming calls · Check Settings");return;}
        awaitCallbackReceiver(android.os.SystemClock.elapsedRealtime()+8000);
    }
    private void awaitCallbackReceiver(long deadline){
        if(isDestroyed()||pendingCallback==null){callbackStarting=false;return;}
        CallService service=CallService.instance;
        if(service==null||!service.isAvailable()){
            if(android.os.SystemClock.elapsedRealtime()>=deadline){callbackStarting=false;pendingCallback=null;callbackProgress("Could not enable incoming calls · Try becoming available first");return;}
            linkHandler.postDelayed(()->awaitCallbackReceiver(deadline),100);return;
        }
        JSONObject request=pendingCallback;Diagnostics.incomingState(this,"callback-request");
        callbackProgress("Sending callback request…");
        worker.execute(()->{try{
            CallEndings.sync(data);Api.call(data,"/api/callbacks",request);
            runOnUiThread(()->{pendingCallback=null;callbackStarting=false;if("Sending callback request…".equals(data.get("callback_status","")))callbackProgress(data.flag("quiet",false)?"Callback requested · Quiet mode is on":"Callback requested · Waiting for your agent…");toast("Callback requested. Incoming calls are enabled.");refresh();});
        }catch(Exception error){runOnUiThread(()->{pendingCallback=null;callbackStarting=false;callbackProgress("Callback request failed · Check your connection and try again");error(error);});}});
    }
    private void toggleAvailability(){CallService service=CallService.instance;if(service==null)enable();else{service.goOffline();root.postDelayed(this::refresh,100);}}
    private void endCall(String id){CallService service=CallService.instance;if(service!=null)service.end(id);else{try{data.endLocally(id);CallEndings.schedule(this);}catch(RuntimeException error){error(error);}}refresh();}
    private void enable(){if(!data.paired())return;permissions(true);}
    private void permissions(boolean start){java.util.ArrayList<String> missing=new java.util.ArrayList<>();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),start?101:102);return;}
        if(start){startReceiver(new Intent(this,CallService.class));root.postDelayed(this::refresh,300);}
        else if(Build.VERSION.SDK_INT>=34&&!getSystemService(NotificationManager.class).canUseFullScreenIntent())startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName())));
        else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
    }
    private void answer(String id){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){pendingAnswer=id;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},103);return;}startReceiver(new Intent(this,CallService.class).setAction(CallService.ANSWER).putExtra("call_id",id));}
    private boolean startReceiver(Intent intent){
        try{data.flag("receiver_recovery",false,true);data.put("availability_error","");startForegroundService(intent);return true;}
        catch(RuntimeException error){Diagnostics.record(this,"receiver-start",error);data.put("availability_error","Android could not start availability. Check app permissions in Settings.");refresh();toast(data.get("availability_error",""));return false;}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);boolean all=grants.length>0;for(int g:grants)all&=g==PackageManager.PERMISSION_GRANTED;if(!all){toast("Enable microphone and notifications in Settings for voice calls");pendingAnswer="";if(request==104){pendingCallback=null;callbackProgress("Callback not sent · Notification permission is needed");}return;}if(request==104){prepareCallback();return;}if(request==101)enable();else if(request==103&&!pendingAnswer.isEmpty()){String id=pendingAnswer;pendingAnswer="";answer(id);}}
    @Override public void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(request==301){if(getPackageManager().canRequestPackageInstalls())installUpdate();else toast("Allow updates from alphaMobile to continue");return;}if(request==302){drawUpdates();return;}}
    private void refresh(){if(isDestroyed()||isFinishing())return;if(deviceStatus!=null)deviceStatus.refresh();MessageAlerts.get(this).viewing(visible,selected);if(CallService.instance!=null)CallService.instance.reconcileCall();if(showIncoming())return;if(page==2&&selected.isEmpty()&&!taskSignature.equals(data.get("tasks","")+data.get("task_sync",""))){refreshSettings();return;}updateHub();if(aiCore!=null)aiCore.connected(data.aiConnected());if(availability!=null){availability.setText(!data.paired()?"Pair with my box":CallService.instance==null?"Become available":"Available · Go offline");ui.availability(availability,CallService.instance!=null);}if(connection!=null)connection.setText(status());CallService s=CallService.instance;if(voiceLabel!=null){JSONObject active=data.call(selected);if(active!=null&&"chat".equals(active.optString("channel")))voiceLabel.setText(messageSending?"Sending…":active.optBoolean("awaiting_agent")?"Waiting for "+active.optString("agent_name")+"…":"Type a message to "+active.optString("agent_name"));else if(s!=null)voiceLabel.setText(s.voiceStatus);}
        if(!selected.isEmpty()){JSONObject call=data.call(selected);if(call==null)return;String old=signature;String now=snapshot();if(!now.equals(old)){
            boolean active="active".equals(call.optString("state"));if(active&&transcript!=null&&composer!=null)fillTranscript(call);else render();signature=now;}
        }else if(page<=1&&!snapshot().equals(signature)){signature=snapshot();fillHistory();}
    }
    private boolean showIncoming(){
        if(!visible||CallService.instance==null||data.flag("quiet",false))return false;
        JSONObject call=data.incoming();if(call==null)return false;String id=call.optString("id");
        if(id.equals(presentedIncoming))return false;
        data.put("callback_status","");presentedIncoming=id;if(id.equals(selected))return false;
        selected=id;render();return true;
    }
    private void pollConnection(boolean connect){
        linkHandler.removeCallbacks(linkRefresh);
        if(!visible)return;
        if(!data.paired()||checkingLink||(!connect&&CallService.instance!=null)){linkHandler.postDelayed(linkRefresh,5000);return;}
        checkingLink=true;worker.execute(()->{try{
            CallEndings.sync(data);
            JSONObject result=Api.request(data.base(),connect?"/api/connect":"/api/connection",data.token(),connect?new JSONObject():null,8000);
            String oldTasks=data.get("tasks","")+data.get("task_sync","");data.snapshot(result);Diagnostics.upload(this,data);runOnUiThread(()->appUpdates.automaticCheck());
            boolean changed=!oldTasks.equals(data.get("tasks","")+data.get("task_sync",""));
            runOnUiThread(()->{refresh();if(visible&&page==2&&selected.isEmpty()&&changed)refreshSettings();});
        }catch(Exception e){data.flag("ai_connected",false,true);runOnUiThread(this::refresh);}
        finally{runOnUiThread(()->{checkingLink=false;if(visible)linkHandler.postDelayed(linkRefresh,5000);});}});
    }
    private void fetchCall(String id){if(!data.paired())return;worker.execute(()->{try{JSONObject call=Api.call(data,"/api/calls/"+id,null);data.saveCall(call);runOnUiThread(this::refresh);}catch(Exception e){error(e);}});}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);render();}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void error(Exception e){runOnUiThread(()->toast(e.getMessage()==null?"Connection failed":e.getMessage()));}
    private void navigateBack(){if(!selected.isEmpty()){selected="";render();}else if(page!=-1)openPage(-1);else finish();}
    // API 33+ uses the registered predictive-back callback; this handles Android 8–12.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){navigateBack();}
    @Override public void onDestroy(){appUpdates.unlisten(updateChanged);linkHandler.removeCallbacksAndMessages(null);unregisterReceiver(updates);worker.shutdownNow();super.onDestroy();}
}
