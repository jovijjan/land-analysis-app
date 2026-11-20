package kr.civilcad.viewer;

import android.app.*; import android.os.*; import android.content.*; import android.database.Cursor; import android.graphics.*; import android.net.Uri; import android.provider.OpenableColumns; import android.view.*; import android.widget.*;
import java.io.*; import java.util.*; import java.util.concurrent.*;

public class MainActivity extends Activity {
  static { System.loadLibrary("civilcad_native"); }
  public static native byte[] decodeDwg(byte[] bytes) throws IOException;
  static final int OPEN=101;
  TextView status; Viewer view; Button measure;
  final ExecutorService worker=Executors.newSingleThreadExecutor();
  int dp(float v){ return (int)(v*getResources().getDisplayMetrics().density+.5f); }
  Button button(String s){Button b=new Button(this); b.setText(s); b.setTextSize(12);b.setAllCaps(false);return b;}
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(14,21,31));
    LinearLayout bar=new LinearLayout(this);
    Button open=button("📁 도면 열기"); measure=button("📏 거리 측정"); Button layers=button("레이어");
    for(Button b:new Button[]{open,measure,layers}) bar.addView(b,new LinearLayout.LayoutParams(0,dp(54),1f));
    status=new TextView(this);status.setTextColor(Color.WHITE);status.setPadding(dp(12),dp(8),dp(12),dp(8));status.setText("DWG 또는 DXF 도면을 선택하세요.");
    view=new Viewer(this);
    root.addView(bar);root.addView(status);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    open.setOnClickListener(v-> {
      Intent it=new Intent(Intent.ACTION_OPEN_DOCUMENT);it.setType("*/*");it.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(it,OPEN);
    });
    measure.setOnClickListener(v->{view.measuring=!view.measuring;measure.setText(view.measuring?"✓ 측정 중":"📏 거리 측정");
      status.setText(view.measuring?"화면을 두 번 터치해 두 점 사이 거리를 측정하세요.":"한 손가락 이동 · 두 손가락 확대/축소");});
    layers.setOnClickListener(v->layerDialog());
  }
  void layerDialog(){
    if(view.drawing==null){Toast.makeText(this,"도면을 먼저 열어주세요.",Toast.LENGTH_SHORT).show();return;}
    String[] names=view.drawing.layers.toArray(new String[0]);boolean[] selected=new boolean[names.length];
    for(int i=0;i<names.length;i++) selected[i]=!view.hidden.contains(names[i]);
    new AlertDialog.Builder(this).setTitle("레이어 켜기/끄기").setMultiChoiceItems(names,selected,(dialog,index,on)->selected[index]=on)
       .setPositiveButton("적용",(dialog,which)->{view.hidden.clear();for(int i=0;i<names.length;i++)if(!selected[i])view.hidden.add(names[i]);view.invalidate();})
       .setNegativeButton("취소",null).show();
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data); if(req!=OPEN||result!=RESULT_OK||data==null)return;
    Uri uri=data.getData();if(uri==null)return;
    String fileName="drawing";
    try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null && c.moveToFirst())fileName=c.getString(0);}catch(Exception ignored){}
    final String name=fileName;
    status.setText("읽는 중: "+name);
    worker.submit(()->{
      try{
        byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
          if(in==null)throw new IOException("읽을 수 없는 파일");byte[] buffer=new byte[32768];int n;
          while((n=in.read(buffer))!=-1){if(out.size()+n>64*1024*1024)throw new IOException("현재 64MB 이하 파일만 지원");out.write(buffer,0,n);}bytes=out.toByteArray();
        }
        boolean dwg=bytes.length>6 && bytes[0]=='A'&&bytes[1]=='C'&&bytes[2]=='1'&&bytes[3]=='0';
        if(!dwg && !name.toLowerCase(Locale.ROOT).endsWith(".dxf"))throw new IOException("DWG 또는 DXF 파일만 지원");
        Dxf.Drawing parsed=Dxf.parse(dwg?decodeDwg(bytes):bytes);
        runOnUiThread(()->{view.setDrawing(parsed);status.setText(name+"   선 "+parsed.lines.size()+"개 / 레이어 "+parsed.layers.size()+"개 · "+parsed.units());});
      } catch(Throwable t){runOnUiThread(()->status.setText("도면 열기 실패: "+t.getMessage()));}
    });
  }
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  final class Viewer extends View {
    Dxf.Drawing drawing;boolean measuring=false;HashSet<String> hidden=new HashSet<>();
    double scale=1,panX=0,panY=0,ox=0,oy=0;double[] first,second;
    float downX,downY,lastX,lastY;boolean moved=false;
    Paint p=new Paint(3);
    ScaleGestureDetector zoom;
    Viewer(Context ctx){super(ctx);zoom=new ScaleGestureDetector(ctx,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
      @Override public boolean onScale(ScaleGestureDetector d){double old=scale;scale=Math.max(1e-8,Math.min(1e7,scale*d.getScaleFactor()));
        panX=d.getFocusX()-(d.getFocusX()-panX)*scale/old;panY=d.getFocusY()-(d.getFocusY()-panY)*scale/old;invalidate();return true;}
    });}
    void setDrawing(Dxf.Drawing d){drawing=d;hidden.clear();first=null;second=null;
      post(()->{ox=d.minX;oy=d.minY;scale=.85*Math.min(getWidth()/Math.max(.01,d.maxX-d.minX),getHeight()/Math.max(.01,d.maxY-d.minY));
        if(!Double.isFinite(scale)||scale<=0)scale=1;
        panX=(getWidth()-(d.maxX-d.minX)*scale)/2;panY=(getHeight()+(d.maxY-d.minY)*scale)/2;invalidate();});
    }
    float x(double px){return (float)((px-ox)*scale+panX);}float y(double py){return (float)(-(py-oy)*scale+panY);}
    double[] world(float sx,float sy){return new double[]{(sx-panX)/scale+ox,-(sy-panY)/scale+oy};}
    @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(Color.rgb(15,24,34));
      p.setColor(Color.WHITE);p.setTextSize(dp(18));
      if(drawing==null){c.drawText("도면 열기를 눌러주세요.",dp(24),getHeight()/2f,p);return;}
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));
      for(Dxf.Line l:drawing.lines){if(hidden.contains(l.layer))continue;
        p.setColor(Color.rgb(100+((l.layer.hashCode()>>>16)&100),120+((l.layer.hashCode()>>>8)&100),140+(l.layer.hashCode()&90)));
        c.drawLine(x(l.x1),y(l.y1),x(l.x2),y(l.y2),p);
      }
      p.setStyle(Paint.Style.FILL);p.setColor(Color.YELLOW);p.setStrokeWidth(dp(2));
      if(first!=null)c.drawCircle(x(first[0]),y(first[1]),dp(5),p);
      if(second!=null){c.drawCircle(x(second[0]),y(second[1]),dp(5),p);c.drawLine(x(first[0]),y(first[1]),x(second[0]),y(second[1]),p);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){zoom.onTouchEvent(e);
      switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN: downX=lastX=e.getX();downY=lastY=e.getY();moved=false;return true;
        case MotionEvent.ACTION_POINTER_DOWN: moved=true;return true;
        case MotionEvent.ACTION_MOVE:
          if(Math.hypot(e.getX()-downX,e.getY()-downY)>dp(8))moved=true;
          if(e.getPointerCount()==1&&!zoom.isInProgress()){panX+=e.getX()-lastX;panY+=e.getY()-lastY;invalidate();}
          lastX=e.getX();lastY=e.getY();return true;
        case MotionEvent.ACTION_UP:
          if(measuring&&drawing!=null&&!moved){double[] pos=world(e.getX(),e.getY());
            if(first==null||second!=null){first=pos;second=null;}else{
              second=pos;double distance=Math.hypot(second[0]-first[0],second[1]-first[1]);
              status.setText(String.format(Locale.KOREA,"두 점 사이 직선거리: %.3f %s",distance,drawing.units()));
            }invalidate();
          }return true;
      }return true;
    }
  }
}
