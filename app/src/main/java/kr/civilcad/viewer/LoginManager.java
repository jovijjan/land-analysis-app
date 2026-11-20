package kr.civilcad.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for a separately deployed Google Apps Script authentication backend.
 * No Sheets API keys, Sheets contents or credentials are included in the APK.
 * This beta provides a clearly-labelled test mode only UNTIL an endpoint is configured.
 */
final class LoginManager {
  interface Listener { void authenticated(); }
  private final Activity activity;
  private final Listener listener;
  private final SharedPreferences settings;
  private final ExecutorService network=Executors.newSingleThreadExecutor();
  private boolean authenticated=false;
  private String username="";
  private String token="";
  LoginManager(Activity activity,Listener listener){
    this.activity=activity;this.listener=listener;
    this.settings=activity.getSharedPreferences("civilcad_auth",Context.MODE_PRIVATE);
  }
  boolean hasEndpoint(){return endpoint().length()>0;}
  boolean requiresLogin(){return hasEndpoint()&&!authenticated;}
  String endpoint(){return settings.getString("login_url","");}
  String user(){return username;}
  private boolean validEndpoint(String value){
    return value.startsWith("https://script.google.com/macros/s/")&&value.endsWith("/exec");
  }
  void start(){
    if(hasEndpoint())showLogin();
  }
  void menu(){
    if(!hasEndpoint()){configure();return;}
    if(!authenticated){showLogin();return;}
    new AlertDialog.Builder(activity).setTitle("로그인: "+username)
      .setItems(new String[]{"로그아웃","로그인 서버 주소 변경","닫기"},(dialog,which)->{
        if(which==0){authenticated=false;token="";username="";showLogin();}
        else if(which==1){authenticated=false;token="";username="";configure();}
      }).show();
  }
  void configure(){
    EditText input=new EditText(activity);
    input.setSingleLine(true);
    input.setText(endpoint());
    input.setHint("https://script.google.com/macros/s/.../exec");
    LinearLayout box=new LinearLayout(activity);box.setPadding(24,12,24,8);
    box.addView(input,new LinearLayout.LayoutParams(-1,-2));
    new AlertDialog.Builder(activity).setTitle("구글 시트 로그인 서버")
      .setMessage("Apps Script 웹앱을 먼저 배포한 후 /exec 주소를 등록하세요. 비밀번호나 시트 접근 키를 입력하지 마세요.")
      .setView(box).setNegativeButton("취소",(d,w)->{if(hasEndpoint())showLogin();})
      .setPositiveButton("저장",(d,w)->{
        String value=input.getText().toString().trim();
        if(!validEndpoint(value)){
          Toast.makeText(activity,"HTTPS Apps Script /exec 주소를 확인하세요.",Toast.LENGTH_LONG).show();
          if(hasEndpoint())showLogin();
          return;
        }
        settings.edit().putString("login_url",value).apply();
        authenticated=false;token="";showLogin();
      }).show();
  }
  void showLogin(){
    if(!hasEndpoint()){configure();return;}
    final LinearLayout body=new LinearLayout(activity);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(32,16,32,8);
    final EditText id=new EditText(activity),password=new EditText(activity);
    id.setSingleLine(true);id.setHint("아이디");
    id.setInputType(InputType.TYPE_CLASS_TEXT);
    password.setSingleLine(true);password.setHint("비밀번호");
    password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    final TextView progress=new TextView(activity);
    progress.setText("구글 시트 인증 서버에 연결합니다.");
    body.addView(id,new LinearLayout.LayoutParams(-1,-2));
    body.addView(password,new LinearLayout.LayoutParams(-1,-2));
    body.addView(progress,new LinearLayout.LayoutParams(-1,-2));
    final AlertDialog dialog=new AlertDialog.Builder(activity)
      .setTitle("CivilCAD 로그인")
      .setView(body)
      .setCancelable(false)
      .setPositiveButton("로그인",null)
      .setNeutralButton("서버 설정",null)
      .setNegativeButton("앱 종료",(d,w)->activity.finish())
      .create();
    dialog.setOnShowListener(ignored->{
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{dialog.dismiss();configure();});
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
        String user=id.getText().toString().trim(),secret=password.getText().toString();
        if(user.length()==0||secret.length()==0){
          progress.setText("아이디와 비밀번호를 입력하세요.");return;
        }
        if(user.length()>100||secret.length()>200){
          progress.setText("아이디 또는 비밀번호가 너무 깁니다.");return;
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        progress.setText("로그인 확인 중...");
        network.submit(()->{
          try{
            JSONObject request=new JSONObject();
            request.put("action","login");request.put("id",user);request.put("password",secret);
            JSONObject answer=post(request);
            activity.runOnUiThread(()->{
              if(activity.isFinishing())return;
              if(answer.optBoolean("ok")&&answer.optString("token").length()>10){
                authenticated=true;username=user;token=answer.optString("token");
                dialog.dismiss();listener.authenticated();
              }else{
                progress.setText(answer.optString("error","아이디 또는 비밀번호를 확인하세요."));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
              }
            });
          }catch(Exception error){
            activity.runOnUiThread(()->{
              if(activity.isFinishing())return;
              progress.setText("인증 서버 연결 실패: "+error.getMessage());
              dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
          }
        });
      });
    });
    dialog.show();
  }
  private JSONObject post(JSONObject input)throws Exception {
    URL url=new URL(endpoint());
    if(!validEndpoint(url.toString()))throw new IllegalArgumentException("허용되지 않은 인증 서버 주소");
    HttpURLConnection conn=(HttpURLConnection)url.openConnection();
    conn.setConnectTimeout(12000);conn.setReadTimeout(15000);
    conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty("Content-Type","application/json; charset=UTF-8");
    byte[] data=input.toString().getBytes(StandardCharsets.UTF_8);
    try(OutputStream out=conn.getOutputStream()){out.write(data);}
    int status=conn.getResponseCode();
    if(status<200||status>=300)throw new IllegalStateException("HTTP "+status);
    if(!conn.getURL().getProtocol().equals("https"))throw new IllegalStateException("HTTPS 연결이 필요합니다.");
    try(InputStream stream=conn.getInputStream();
        ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
      byte[] b=new byte[4096];int n;
      while((n=stream.read(b))!=-1){
        if(buffer.size()+n>30000)throw new IllegalArgumentException("비정상적인 응답 크기");
        buffer.write(b,0,n);
      }
      return new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
    }finally{conn.disconnect();}
  }
  void shutdown(){network.shutdownNow();}
}