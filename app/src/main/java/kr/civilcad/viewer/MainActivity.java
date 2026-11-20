package kr.civilcad.viewer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  static { System.loadLibrary("civilcad_native"); }
  public static native byte[] decodeDwg(byte[] bytes) throws IOException;
  static final int OPEN=101;
  static final int PAN=0, DISTANCE=1, CLOSED_AREA=2, POINT_AREA=3;
  TextView status; Viewer view;
  Button measureButton, closedButton, pointButton;
  String drawingName="";
  final ExecutorService worker=Executors.newSingleThreadExecutor();

  int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
  Button button(String title){
    Button b=new Button(this);b.setText(title);b.setTextSize(11);
    b.setAllCaps(false);b.setSingleLine(true);return b;
  }
  void addButton(LinearLayout bar,Button b){
    bar.addView(b,new LinearLayout.LayoutParams(0,dp(50),1f));
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(14,21,31));
    LinearLayout fileBar=new LinearLayout(this);
    Button open=button("📁 도면 열기"),layers=button("레이어"),units=button("단위");
    addButton(fileBar,open);addButton(fileBar,layers);addButton(fileBar,units);
    LinearLayout toolBar=new LinearLayout(this);
    measureButton=button("📏 거리");
    closedButton=button("폐합 면적");
    pointButton=button("점 면적");
    Button done=button("완료 / 취소");
    addButton(toolBar,measureButton);addButton(toolBar,closedButton);
    addButton(toolBar,pointButton);addButton(toolBar,done);
    status=new TextView(this);
    status.setTextColor(Color.WHITE);
    status.setTextSize(13);status.setPadding(dp(12),dp(8),dp(12),dp(8));
    status.setText("DWG 또는 DXF 도면을 열어주세요.");
    view=new Viewer(this);
    root.addView(fileBar);root.addView(toolBar);root.addView(status);
    root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);

    open.setOnClickListener(v->{
      Intent it=new Intent(Intent.ACTION_OPEN_DOCUMENT);
      it.setType("*/*");it.addCategory(Intent.CATEGORY_OPENABLE);
      startActivityForResult(it,OPEN);
    });
    measureButton.setOnClickListener(v->setMode(DISTANCE));
    closedButton.setOnClickListener(v->setMode(CLOSED_AREA));
    pointButton.setOnClickListener(v->setMode(POINT_AREA));
    done.setOnClickListener(v->{
      if(view.mode==POINT_AREA&&view.areaPoints.size()>=3){
        double area=Dxf.Polygon.area(view.areaPoints);
        status.setText("선택한 점 "+view.areaPoints.size()+"개 · 면적 "+view.drawing.area(area)+" (평면 면적)");
        view.areaFinished=true;view.invalidate();
      } else {
        view.mode=PAN;view.first=null;view.second=null;
        view.areaPoints.clear();view.selected=null;view.areaFinished=false;
        status.setText("이동 및 확대 모드");
        view.invalidate();
      }
      updateToolText();
    });
    layers.setOnClickListener(v->layerDialog());
    units.setOnClickListener(v->unitDialog());
  }
  void updateToolText(){
    measureButton.setText(view.mode==DISTANCE?"✓ 거리":"📏 거리");
    closedButton.setText(view.mode==CLOSED_AREA?"✓ 폐합 면적":"폐합 면적");
    pointButton.setText(view.mode==POINT_AREA?"✓ 점 면적":"점 면적");
  }
  void setMode(int newMode) {
    if(view.drawing==null){status.setText("도면을 먼저 열어주세요.");return;}
    view.mode=view.mode==newMode?PAN:newMode;
    view.first=null;view.second=null;
    view.selected=null;view.areaPoints.clear();view.areaFinished=false;
    switch(view.mode){
      case DISTANCE:status.setText("두 점을 선택하세요. 선 끝점에 자동 스냅됩니다.");break;
      case CLOSED_AREA:status.setText("폐합 폴리라인 내부 또는 경계선을 누르세요.");break;
      case POINT_AREA:status.setText("면적 경계점을 차례로 누른 다음 [완료]를 누르세요.");break;
      default:status.setText("한 손가락 이동 · 두 손가락 확대/축소");
    }
    updateToolText();view.invalidate();
  }
  void layerDialog() {
    if(view.drawing==null){status.setText("도면을 먼저 열어주세요.");return;}
    String[] names=view.drawing.layers.toArray(new String[0]);
    boolean[] selected=new boolean[names.length];
    for(int i=0;i<names.length;i++)selected[i]=!view.hidden.contains(names[i]);
    new AlertDialog.Builder(this).setTitle("레이어 켜기·끄기")
      .setMultiChoiceItems(names,selected,(dialog,index,on)->selected[index]=on)
      .setPositiveButton("적용",(dialog,which)->{
        view.hidden.clear();
        for(int i=0;i<names.length;i++)if(!selected[i])view.hidden.add(names[i]);
        view.invalidate();
      }).setNegativeButton("취소",null).show();
  }
  void unitDialog() {
    if(view.drawing==null){status.setText("도면을 먼저 열어주세요.");return;}
    String[] names={"DWG 기본 단위 ("+view.drawing.units()+")","m (미터)","mm (밀리미터)","cm (센티미터)","ft (피트)","inch (인치)","도면 단위 (미지정)"};
    int[] values={-1,6,4,5,2,1,0};
    new AlertDialog.Builder(this).setTitle("도면의 좌표 단위")
      .setSingleChoiceItems(names,0,(dialog,index)->{
        view.drawing.unitOverride=values[index];
        status.setText("단위: "+view.drawing.units()+" · 도면 좌표를 기준으로 거리·면적을 환산합니다.");
        dialog.dismiss();
      }).setNegativeButton("취소",null).show();
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(req!=OPEN||result!=RESULT_OK||data==null)return;
    Uri uri=data.getData();if(uri==null)return;
    String fileName="drawing";
    try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
      if(c!=null&&c.moveToFirst())fileName=c.getString(0);
    }catch(Exception ignored){}
    final String name=fileName;status.setText("도면을 읽는 중: "+name);
    worker.submit(()->{
      try{
        byte[] bytes;
        try(InputStream in=getContentResolver().openInputStream(uri);
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
          if(in==null)throw new IOException("파일을 읽을 수 없습니다.");
          byte[] buffer=new byte[32768];int n;
          while((n=in.read(buffer))!=-1){
            if(out.size()+n>64*1024*1024)throw new IOException("현재 64MB 이하 도면만 지원합니다.");
            out.write(buffer,0,n);
          }
          bytes=out.toByteArray();
        }
        boolean dwg=bytes.length>6&&bytes[0]=='A'&&bytes[1]=='C'&&bytes[2]=='1'&&bytes[3]=='0';
        if(!dwg&&!name.toLowerCase(Locale.ROOT).endsWith(".dxf"))
          throw new IOException("DWG 또는 DXF만 지원합니다.");
        Dxf.Drawing parsed=Dxf.parse(dwg?decodeDwg(bytes):bytes);
        runOnUiThread(()->{
          drawingName=name;view.setDrawing(parsed);view.mode=PAN;updateToolText();
          status.setText(name+" · 선 "+parsed.lines.size()+" · 문자 "+parsed.texts.size()
            +" · 블록삽입 "+parsed.inserts+" · 면적객체 "+parsed.polygons.size()
            +" · "+parsed.units());
        });
      }catch(Throwable t){runOnUiThread(()->status.setText("도면 열기 실패: "+t.getMessage()));}
    });
  }
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

  final class Viewer extends View {
    Dxf.Drawing drawing;
    int mode=PAN;HashSet<String> hidden=new HashSet<>();
    Dxf.Polygon selected;
    final ArrayList<double[]> areaPoints=new ArrayList<>();
    boolean areaFinished=false;
    double[] first,second;
    double scale=1,panX=0,panY=0,originX=0,originY=0;
    float downX,downY,lastX,lastY;boolean moved=false,multiTouch=false;
    final Paint linePaint=new Paint(3),textPaint=new Paint(3),overlay=new Paint(3);
    final ScaleGestureDetector zoom;
    Viewer(Context ctx){
      super(ctx);
      zoom=new ScaleGestureDetector(ctx,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
        @Override public boolean onScale(ScaleGestureDetector detector){
          double old=scale;
          scale=Math.max(1e-8,Math.min(1e7,scale*detector.getScaleFactor()));
          panX=detector.getFocusX()-(detector.getFocusX()-panX)*scale/old;
          panY=detector.getFocusY()-(detector.getFocusY()-panY)*scale/old;
          invalidate();return true;
        }
      });
    }
    void setDrawing(Dxf.Drawing d){
      drawing=d;hidden.clear();first=null;second=null;selected=null;areaPoints.clear();areaFinished=false;
      post(()->{
        originX=Double.isFinite(d.minX)?d.minX:0;
        originY=Double.isFinite(d.minY)?d.minY:0;
        double w=Math.max(.01,d.maxX-d.minX),h=Math.max(.01,d.maxY-d.minY);
        scale=.88*Math.min((double)getWidth()/w,(double)getHeight()/h);
        if(!Double.isFinite(scale)||scale<=0)scale=1;
        panX=(getWidth()-w*scale)/2;
        panY=(getHeight()+h*scale)/2;
        invalidate();
      });
    }
    float sx(double x){return (float)((x-originX)*scale+panX);}
    float sy(double y){return (float)(-(y-originY)*scale+panY);}
    double[] world(float x,float y){return new double[]{(x-panX)/scale+originX,-(y-panY)/scale+originY};}
    int layerColor(String layer){
      int hash=layer.hashCode();
      return Color.rgb(112+((hash>>>16)&105),110+((hash>>>8)&115),120+(hash&100));
    }
    @Override protected void onDraw(Canvas canvas){
      super.onDraw(canvas);
      canvas.drawColor(Color.rgb(15,24,34));
      if(drawing==null){
        textPaint.setColor(Color.WHITE);textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(dp(18));
        canvas.drawText("도면 열기를 눌러주세요.",dp(24),getHeight()/2f,textPaint);
        return;
      }
      linePaint.setStyle(Paint.Style.STROKE);linePaint.setStrokeWidth(dp(1));
      String lastLayer=null;int color=Color.WHITE;
      for(Dxf.Line line:drawing.lines){
        if(hidden.contains(line.layer))continue;
        if(!line.layer.equals(lastLayer)){lastLayer=line.layer;color=layerColor(line.layer);}
        linePaint.setColor(color);
        canvas.drawLine(sx(line.x1),sy(line.y1),sx(line.x2),sy(line.y2),linePaint);
      }
      textPaint.setStyle(Paint.Style.FILL);
      textPaint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
      for(Dxf.Label item:drawing.texts){
        if(hidden.contains(item.layer))continue;
        float size=(float)(item.size*scale);
        if(!Float.isFinite(size)||size<2||size>getHeight()*3)continue;
        textPaint.setColor(layerColor(item.layer));
        textPaint.setTextSize(size);
        float x=sx(item.x),y=sy(item.y);
        canvas.save();canvas.rotate((float)-Math.toDegrees(item.angle),x,y);
        String[] lines=item.text.split("\n",-1);
        for(int i=0;i<Math.min(16,lines.length);i++){
          if(!lines[i].isEmpty())canvas.drawText(lines[i],x,y+i*size*1.3f,textPaint);
        }
        canvas.restore();
      }
      overlay.setColor(Color.rgb(45,236,195));overlay.setStyle(Paint.Style.STROKE);
      overlay.setStrokeWidth(dp(2.5f));
      if(selected!=null&&!hidden.contains(selected.layer))drawPolygon(canvas,selected.points,true);
      if(areaPoints.size()>0){
        boolean closed=areaFinished&&areaPoints.size()>=3;
        drawPolygon(canvas,areaPoints,closed);
        overlay.setStyle(Paint.Style.FILL);
        for(double[] pt:areaPoints)canvas.drawCircle(sx(pt[0]),sy(pt[1]),dp(4),overlay);
        overlay.setStyle(Paint.Style.STROKE);
      }
      if(first!=null){
        overlay.setColor(Color.YELLOW);overlay.setStyle(Paint.Style.FILL);
        canvas.drawCircle(sx(first[0]),sy(first[1]),dp(5),overlay);
        if(second!=null){
          canvas.drawCircle(sx(second[0]),sy(second[1]),dp(5),overlay);
          overlay.setStrokeWidth(dp(2));overlay.setStyle(Paint.Style.STROKE);
          canvas.drawLine(sx(first[0]),sy(first[1]),sx(second[0]),sy(second[1]),overlay);
        }
      }
    }
    void drawPolygon(Canvas canvas,List<double[]> points,boolean closed){
      if(points.size()==0)return;
      Path path=new Path();path.moveTo(sx(points.get(0)[0]),sy(points.get(0)[1]));
      for(int i=1;i<points.size();i++)path.lineTo(sx(points.get(i)[0]),sy(points.get(i)[1]));
      if(closed)path.close();
      overlay.setColor(Color.rgb(45,236,195));overlay.setStyle(Paint.Style.STROKE);
      overlay.setStrokeWidth(dp(2.5f));canvas.drawPath(path,overlay);
      if(closed){
        overlay.setStyle(Paint.Style.FILL);overlay.setColor(Color.argb(42,45,236,195));
        canvas.drawPath(path,overlay);
      }
      overlay.setStyle(Paint.Style.STROKE);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
      zoom.onTouchEvent(e);
      switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:
          downX=lastX=e.getX();downY=lastY=e.getY();moved=false;multiTouch=false;return true;
        case MotionEvent.ACTION_POINTER_DOWN:
          moved=true;multiTouch=true;return true;
        case MotionEvent.ACTION_POINTER_UP:
          multiTouch=true;moved=true;return true;
        case MotionEvent.ACTION_MOVE:
          if(Math.hypot(e.getX()-downX,e.getY()-downY)>dp(8))moved=true;
          if(e.getPointerCount()==1&&!zoom.isInProgress()&&!multiTouch){
            panX+=e.getX()-lastX;panY+=e.getY()-lastY;invalidate();
          }
          lastX=e.getX();lastY=e.getY();return true;
        case MotionEvent.ACTION_UP:
          if(mode!=PAN&&drawing!=null&&!moved&&!multiTouch)pick(e.getX(),e.getY());
          return true;
      }
      return true;
    }
    void pick(float x,float y){
      double[] pos=world(x,y);
      double tolerance=dp(18)/Math.max(scale,1e-8);
      if(mode==CLOSED_AREA){
        selected=drawing.findPolygon(pos[0],pos[1],tolerance,hidden);
        if(selected==null)status.setText("폐합 폴리라인이 없습니다. [점 면적]을 사용해 보세요.");
        else status.setText("선택 면적: "+drawing.area(selected.area)+" · "+selected.layer+" · 평면 면적");
        invalidate();return;
      }
      pos=drawing.snap(pos[0],pos[1],tolerance,hidden);
      if(mode==DISTANCE){
        if(first==null||second!=null){first=pos;second=null;
          status.setText("첫 번째 점 선택됨. 두 번째 점을 누르세요.");
        }else{
          second=pos;
          status.setText("두 점 사이 거리: "+drawing.distance(Math.hypot(
            first[0]-second[0],first[1]-second[1]))+" (평면 직선거리)");
        }
      }
      if(mode==POINT_AREA){
        if(areaFinished){areaPoints.clear();areaFinished=false;}
        if(areaPoints.size()<2000){
          areaPoints.add(pos);
          status.setText("점 "+areaPoints.size()+"개 선택됨 · 3점 이상 선택 후 [완료]");
        }
      }
      invalidate();
    }
  }
}
