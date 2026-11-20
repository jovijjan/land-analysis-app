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
  static final int OPEN=101;
  static final int PAN=0, DISTANCE=1, POINT_AREA=3;
  TextView status; Viewer view;
  Basic3DView view3d;
  FrameLayout viewerHost;
  LinearLayout toolBar;
  Button measureButton, pointButton, switchViewButton;
  boolean threeD=false;
  String drawingName="";
  final ExecutorService worker=Executors.newSingleThreadExecutor();
  final Handler handler=new Handler(Looper.getMainLooper());
  String pendingDecodeJob=null;
  final BroadcastReceiver decodeReceiver=new BroadcastReceiver(){
    @Override public void onReceive(Context context,Intent intent){
      String job=intent.getStringExtra("job");
      if(job==null||!job.equals(pendingDecodeJob))return;
      pendingDecodeJob=null;
      String error=intent.getStringExtra("error");
      if(error!=null){
        status.setText("DWG 읽기 실패: "+error);
        return;
      }
      String output=intent.getStringExtra("output");
      if(output==null){status.setText("DWG 변환 결과가 없습니다.");return;}
      File file=new File(output);
      status.setText("도면 요소를 준비하는 중...");
      worker.submit(()->parseAndShow(file,drawingName,true));
    }
  };


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
    registerReceiver(decodeReceiver,new IntentFilter(DwgDecoderService.ACTION));
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(14,21,31));
    LinearLayout fileBar=new LinearLayout(this);
    Button open=button("📁 도면 열기"),layers=button("레이어"),units=button("단위");
    switchViewButton=button("3D 뷰");
    addButton(fileBar,open);addButton(fileBar,layers);addButton(fileBar,units);addButton(fileBar,switchViewButton);
    toolBar=new LinearLayout(this);
    measureButton=button("📏 거리");
    pointButton=button("점 면적");
    Button done=button("완료 / 취소");
    addButton(toolBar,measureButton);
    addButton(toolBar,pointButton);addButton(toolBar,done);
    status=new TextView(this);
    status.setTextColor(Color.WHITE);
    status.setTextSize(13);status.setPadding(dp(12),dp(8),dp(12),dp(8));
    status.setText("DWG 또는 DXF 도면을 열어주세요.");
    view=new Viewer(this);
    view3d=new Basic3DView(this);
    viewerHost=new FrameLayout(this);
    viewerHost.addView(view,new FrameLayout.LayoutParams(-1,-1));
    viewerHost.addView(view3d,new FrameLayout.LayoutParams(-1,-1));
    view3d.setVisibility(View.GONE);
    root.addView(fileBar);root.addView(toolBar);root.addView(status);
    root.addView(viewerHost,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);

    open.setOnClickListener(v->{
      Intent it=new Intent(Intent.ACTION_OPEN_DOCUMENT);
      it.setType("*/*");it.addCategory(Intent.CATEGORY_OPENABLE);
      startActivityForResult(it,OPEN);
    });
    measureButton.setOnClickListener(v->setMode(DISTANCE));
    pointButton.setOnClickListener(v->setMode(POINT_AREA));
    switchViewButton.setOnClickListener(v->toggleView());
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
    handleOpenIntent(getIntent());
  }
  void toggleView(){
    if(view.drawing==null){status.setText("도면을 먼저 열어주세요.");return;}
    threeD=!threeD;
    view.mode=PAN;view.first=null;view.second=null;view.areaPoints.clear();view.areaFinished=false;
    view.setVisibility(threeD?View.GONE:View.VISIBLE);
    view3d.setVisibility(threeD?View.VISIBLE:View.GONE);
    toolBar.setVisibility(threeD?View.GONE:View.VISIBLE);
    switchViewButton.setText(threeD?"2D 뷰":"3D 뷰");
    if(threeD){
      view3d.setDrawing(view.drawing,view.hidden);
      status.setText((view.drawing.maxZ>view.drawing.minZ+1e-8)
        ?"3D 와이어프레임 · 손가락 회전 / 두 손가락 확대"
        :"3D 와이어프레임 · 이 도면에는 Z 높이 차이가 없습니다.");
    }else status.setText("2D 모드 · 확대/이동/거리/점면적");
    updateToolText();
  }
  void updateToolText(){
    measureButton.setText(view.mode==DISTANCE?"✓ 거리":"📏 거리");
    pointButton.setText(view.mode==POINT_AREA?"✓ 점 면적":"점 면적");
  }
  void setMode(int newMode) {
    if(view.drawing==null){status.setText("도면을 먼저 열어주세요.");return;}
    view.mode=view.mode==newMode?PAN:newMode;
    view.first=null;view.second=null;
    view.selected=null;view.areaPoints.clear();view.areaFinished=false;
    switch(view.mode){
      case DISTANCE:status.setText("두 점을 선택하세요. 선 끝점에 자동 스냅됩니다.");break;
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
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("레이어 표시 (실시간)")
      .setMultiChoiceItems(names,selected,(d,index,checked)->{
        if(checked)view.hidden.remove(names[index]);
        else view.hidden.add(names[index]);
        view.invalidate();
        view3d.invalidate();
      })
      .setPositiveButton("닫기",(d,w)->{}).create();
    dialog.setOnShowListener(x->{
      Window window=dialog.getWindow();
      if(window!=null){
        android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
        window.setLayout((int)(metrics.widthPixels*.88f),(int)(metrics.heightPixels*.50f));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs=window.getAttributes();
        attrs.dimAmount=.10f;
        window.setAttributes(attrs);
      }
    });
    dialog.show();
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
    openUri(data.getData());
  }
  @Override protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);
    setIntent(intent);
    handleOpenIntent(intent);
  }
  void handleOpenIntent(Intent intent){
    if(intent==null)return;
    String action=intent.getAction();
    Uri uri=null;
    if(Intent.ACTION_VIEW.equals(action))uri=intent.getData();
    else if(Intent.ACTION_SEND.equals(action))uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
    if(uri!=null)openUri(uri);
  }
  void openUri(Uri uri){
    if(uri==null)return;
    String fileName="drawing";
    try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
      if(c!=null&&c.moveToFirst())fileName=c.getString(0);
    }catch(Exception ignored){}
    final String name=fileName;
    drawingName=name;
    pendingDecodeJob=null;
    status.setText("파일 확인 중: "+name);
    worker.submit(()->{
      File input=null,output=null;
      try{
        input=File.createTempFile("civilcad_input_",".cad",getCacheDir());
        try(InputStream in=getContentResolver().openInputStream(uri);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(input),65536)){
          if(in==null)throw new IOException("도면 파일을 열 수 없습니다.");
          byte[] buffer=new byte[65536];int n;long total=0;
          while((n=in.read(buffer))!=-1){
            total+=n;
            if(total>64L*1024*1024)throw new IOException("64MB 이하 DWG 파일만 지원합니다.");
            out.write(buffer,0,n);
          }
        }
        boolean dwg=false;
        try(InputStream header=new FileInputStream(input)){
          byte[] magic=new byte[6];
          if(header.read(magic)==6){
            dwg=magic[0]=='A'&&magic[1]=='C'&&magic[2]=='1'&&magic[3]=='0';
          }
        }
        if(!dwg&&!name.toLowerCase(Locale.ROOT).endsWith(".dxf"))
          throw new IOException("DWG 또는 DXF 파일만 열 수 있습니다.");
        if(dwg){
          output=File.createTempFile("civilcad_output_",".dxf",getCacheDir());
          final File inFile=input,outFile=output;
          final String job=UUID.randomUUID().toString();
          runOnUiThread(()->{
            pendingDecodeJob=job;
            status.setText("DWG를 안전하게 해석하는 중: "+name);
            Intent service=new Intent(this,DwgDecoderService.class);
            service.putExtra("job",job);
            service.putExtra("input",inFile.getAbsolutePath());
            service.putExtra("output",outFile.getAbsolutePath());
            try{
              if(startService(service)==null)throw new IOException("DWG 해석 서비스를 실행할 수 없습니다.");
            }catch(Exception e){
              pendingDecodeJob=null;
              status.setText("DWG 해석 시작 실패: "+e.getMessage());
              inFile.delete();outFile.delete();
            }
            handler.postDelayed(()->{
              if(job.equals(pendingDecodeJob)){
                pendingDecodeJob=null;
                status.setText("DWG 읽기 시간이 초과됐습니다. 도면을 단순화하거나 용량을 확인해 주세요.");
                inFile.delete();outFile.delete();
              }
            },120000);
          });
        }else{
          parseAndShow(input,name,true);
        }
      }catch(Throwable error){
        if(input!=null)input.delete();
        if(output!=null)output.delete();
        final String message=error.getMessage();
        runOnUiThread(()->status.setText("도면 열기 실패: "+message));
      }
    });
  }
  void parseAndShow(File input,String name,boolean deleteWhenDone){
    try{
      Dxf.Drawing parsed=Dxf.parse(input);
      runOnUiThread(()->{
        view.setDrawing(parsed);
        view3d.setDrawing(parsed,view.hidden);
        if(threeD){
          threeD=false;
          view3d.setVisibility(View.GONE);view.setVisibility(View.VISIBLE);
          toolBar.setVisibility(View.VISIBLE);switchViewButton.setText("3D 뷰");
        }
        view.mode=PAN;updateToolText();
        status.setText(name+" · 선 "+parsed.lines.size()+" · 문자 "+parsed.texts.size()
            +" · 블록 "+parsed.inserts+" · 면적 "+parsed.polygons.size()
            +" · "+parsed.units());
      });
    }catch(Throwable error){
      final String message=error.getMessage();
      runOnUiThread(()->status.setText("도면 처리 실패: "+message));
    }finally{
      if(deleteWhenDone)input.delete();
    }
  }
  @Override protected void onDestroy(){
    unregisterReceiver(decodeReceiver);
    pendingDecodeJob=null;
    worker.shutdownNow();
    super.onDestroy();
  }
  final class Viewer extends View {
    static final class Batch { final float[] coords=new float[4096];int count; }
    final HashMap<Integer,Batch> batches=new HashMap<>();
    boolean moving=false;
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

      linePaint.setStyle(Paint.Style.STROKE);
      linePaint.setStrokeWidth(dp(1));
      // Query spatial tiles intersecting the display. Avoid one Canvas call per segment.
      double[] near=world(-16,getHeight()+16),far=world(getWidth()+16,-16);
      Dxf.IntBag visible=drawing.index==null?null:drawing.index.query(
        Math.min(near[0],far[0]),Math.min(near[1],far[1]),
        Math.max(near[0],far[0]),Math.max(near[1],far[1]));
      if(visible!=null) {
        for(Batch batch:batches.values())batch.count=0;
        final int cap=moving?60000:180000;
        final int stride=Math.max(1,(visible.size+cap-1)/cap);
        for(int k=0;k<visible.size;k+=stride){
          Dxf.Line line=drawing.lines.get(visible.values[k]);
          if(hidden.contains(line.layer))continue;
          float x1=sx(line.x1),y1=sy(line.y1),x2=sx(line.x2),y2=sy(line.y2);
          float margin=dp(16);
          if((x1 < -margin&&x2 < -margin)||(x1>getWidth()+margin&&x2>getWidth()+margin)
              ||(y1 < -margin&&y2 < -margin)||(y1>getHeight()+margin&&y2>getHeight()+margin))continue;
          if(Math.abs(x1-x2)<.35f&&Math.abs(y1-y2)<.35f)continue;
          int color=layerColor(line.layer);
          Batch batch=batches.get(color);
          if(batch==null){batch=new Batch();batches.put(color,batch);}
          if(batch.count==batch.coords.length){
            linePaint.setColor(color);
            canvas.drawLines(batch.coords,0,batch.count,linePaint);
            batch.count=0;
          }
          batch.coords[batch.count++]=x1;
          batch.coords[batch.count++]=y1;
          batch.coords[batch.count++]=x2;
          batch.coords[batch.count++]=y2;
        }
        for(Map.Entry<Integer,Batch> item:batches.entrySet()){
          Batch batch=item.getValue();
          if(batch.count>0){
            linePaint.setColor(item.getKey());
            canvas.drawLines(batch.coords,0,batch.count,linePaint);
            batch.count=0;
          }
        }
      }
      textPaint.setStyle(Paint.Style.FILL);
      textPaint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
      int displayedText=0;
      for(Dxf.Label item:drawing.texts){
        if(hidden.contains(item.layer))continue;
        float size=(float)(item.size*scale);
        if(!Float.isFinite(size)||size<3||size>getHeight()*2)continue;
        float x=sx(item.x),y=sy(item.y);
        float margin=Math.min(getWidth(),size*Math.min(70,item.text.length()));
        if(x < -margin||x>getWidth()+dp(16)||y < -size*3||y>getHeight()+size*3)continue;
        if(++displayedText>2200)break; // Avoid text floods in a full drawing view.
        textPaint.setColor(layerColor(item.layer));
        textPaint.setTextSize(size);
        canvas.save();
        canvas.rotate((float)-Math.toDegrees(item.angle),x,y);
        String[] textLines=item.text.split("\n",-1);
        for(int i=0;i<Math.min(16,textLines.length);i++){
          if(!textLines[i].isEmpty())canvas.drawText(textLines[i],x,y+i*size*1.3f,textPaint);
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
          moving=true;downX=lastX=e.getX();downY=lastY=e.getY();moved=false;multiTouch=false;return true;
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
          moving=false;
          if(mode!=PAN&&drawing!=null&&!moved&&!multiTouch)pick(e.getX(),e.getY());
          invalidate();return true;
        case MotionEvent.ACTION_CANCEL:
          moving=false;invalidate();return true;
      }
      return true;
    }
    void pick(float x,float y){
      double[] pos=world(x,y);
      double tolerance=dp(18)/Math.max(scale,1e-8);
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
