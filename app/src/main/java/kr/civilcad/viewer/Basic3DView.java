package kr.civilcad.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Basic Z-aware wireframe viewer.
 * Supports actual Z coordinates from LINE, 3D POLYLINE and 3DFACE.
 * This deliberately does not claim to support AutoCAD ACIS solids/surfaces.
 */
public final class Basic3DView extends View {
  private Dxf.Drawing drawing;
  private Set<String> hidden;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final HashMap<Integer,FloatBuffer> buffers=new HashMap<>();
  private final ScaleGestureDetector zoom;
  private double yaw=Math.toRadians(45),elevation=Math.toRadians(42),pixelsPerUnit=1;
  private double cx=0,cy=0,cz=0,panX=0,panY=0;
  private float lastX,lastY;
  private boolean scaling=false;

  private static final class FloatBuffer {
    final float[] values=new float[4096];
    int count=0;
  }
  public Basic3DView(Context context) {
    super(context);
    setLayerType(View.LAYER_TYPE_HARDWARE,null);
    paint.setStrokeWidth(1.25f*getResources().getDisplayMetrics().density);
    paint.setStyle(Paint.Style.STROKE);
    zoom=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
      @Override public boolean onScale(ScaleGestureDetector detector) {
        pixelsPerUnit=Math.max(1e-9,Math.min(1e9,pixelsPerUnit*detector.getScaleFactor()));
        invalidate();
        return true;
      }
      @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
        scaling=true;return true;
      }
    });
  }
  public void setDrawing(Dxf.Drawing data, Set<String> hiddenLayers) {
    drawing=data;hidden=hiddenLayers;
    yaw=Math.toRadians(45);elevation=Math.toRadians(42);panX=0;panY=0;
    if(data!=null&&Double.isFinite(data.minX)){
      cx=(data.minX+data.maxX)*.5;
      cy=(data.minY+data.maxY)*.5;
      double minZ=Double.isFinite(data.minZ)?data.minZ:0;
      double maxZ=Double.isFinite(data.maxZ)?data.maxZ:0;
      cz=(minZ+maxZ)*.5;
      post(this::fit);
    }
    invalidate();
  }
  private void fit(){
    if(drawing==null||getWidth()==0||getHeight()==0)return;
    double x=(drawing.maxX-drawing.minX)*.5;
    double y=(drawing.maxY-drawing.minY)*.5;
    double z=Math.max(0,drawing.maxZ-drawing.minZ)*.5;
    double maxW=0,maxH=0;
    for(int ix=-1;ix<=1;ix+=2)for(int iy=-1;iy<=1;iy+=2)for(int iz=-1;iz<=1;iz+=2){
      double dx=ix*x,dy=iy*y,dz=iz*z;
      double u=Math.cos(yaw)*dx-Math.sin(yaw)*dy;
      double depth=Math.sin(yaw)*dx+Math.cos(yaw)*dy;
      double v=depth*Math.sin(elevation)-dz*Math.cos(elevation);
      maxW=Math.max(maxW,Math.abs(u));maxH=Math.max(maxH,Math.abs(v));
    }
    pixelsPerUnit=.84*Math.min(getWidth()/(Math.max(.01,maxW)*2),getHeight()/(Math.max(.01,maxH)*2));
    if(!Double.isFinite(pixelsPerUnit)||pixelsPerUnit<=0)pixelsPerUnit=1;
    invalidate();
  }
  private double projectedX(double x,double y) {
    double dx=x-cx,dy=y-cy;
    return Math.cos(yaw)*dx-Math.sin(yaw)*dy;
  }
  private double projectedY(double x,double y,double z) {
    double dx=x-cx,dy=y-cy;
    double depth=Math.sin(yaw)*dx+Math.cos(yaw)*dy;
    return depth*Math.sin(elevation)-(z-cz)*Math.cos(elevation);
  }
  private float px(double x,double y){
    return (float)(getWidth()*.5+panX+projectedX(x,y)*pixelsPerUnit);
  }
  private float py(double x,double y,double z){
    return (float)(getHeight()*.5+panY+projectedY(x,y,z)*pixelsPerUnit);
  }
  private int color(String layer){
    int h=layer.hashCode();
    return Color.rgb(110+((h>>>16)&100),110+((h>>>8)&110),120+(h&105));
  }
  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawColor(Color.rgb(14,21,31));
    if(drawing==null)return;
    for(FloatBuffer buffer:buffers.values())buffer.count=0;
    int total=drawing.lines.size();
    // Adaptive sampling avoids millions of draw calls on a small mobile display.
    int cap=scaling?40000:100000;
    int stride=Math.max(1,(total+cap-1)/cap);
    float margin=64*getResources().getDisplayMetrics().density;
    for(int k=0;k<total;k+=stride){
      Dxf.Line line=drawing.lines.get(k);
      if(hidden!=null&&hidden.contains(line.layer))continue;
      float x1=px(line.x1,line.y1),y1=py(line.x1,line.y1,line.z1);
      float x2=px(line.x2,line.y2),y2=py(line.x2,line.y2,line.z2);
      if((x1< -margin&&x2< -margin)||(x1>getWidth()+margin&&x2>getWidth()+margin)
        ||(y1< -margin&&y2< -margin)||(y1>getHeight()+margin&&y2>getHeight()+margin))continue;
      if(Math.abs(x1-x2)<.3f&&Math.abs(y1-y2)<.3f)continue;
      int c=color(line.layer);
      FloatBuffer buffer=buffers.get(c);
      if(buffer==null){buffer=new FloatBuffer();buffers.put(c,buffer);}
      if(buffer.count==buffer.values.length){
        paint.setColor(c);canvas.drawLines(buffer.values,0,buffer.count,paint);buffer.count=0;
      }
      buffer.values[buffer.count++]=x1;buffer.values[buffer.count++]=y1;
      buffer.values[buffer.count++]=x2;buffer.values[buffer.count++]=y2;
    }
    for(Map.Entry<Integer,FloatBuffer> pair:buffers.entrySet()){
      FloatBuffer b=pair.getValue();
      if(b.count!=0){paint.setColor(pair.getKey());canvas.drawLines(b.values,0,b.count,paint);b.count=0;}
    }
    drawAxes(canvas);
  }
  private void drawAxes(Canvas canvas){
    float ox=58*getResources().getDisplayMetrics().density;
    float oy=58*getResources().getDisplayMetrics().density;
    float length=26*getResources().getDisplayMetrics().density;
    paint.setStrokeWidth(2*getResources().getDisplayMetrics().density);
    paint.setTextSize(13*getResources().getDisplayMetrics().density);
    paint.setStyle(Paint.Style.STROKE);
    float cos=(float)Math.cos(yaw),sin=(float)Math.sin(yaw);
    paint.setColor(Color.RED);canvas.drawLine(ox,oy,ox+length*cos,oy+length*sin*(float)Math.sin(elevation),paint);
    paint.setColor(Color.GREEN);canvas.drawLine(ox,oy,ox-length*sin,oy+length*cos*(float)Math.sin(elevation),paint);
    paint.setColor(Color.CYAN);canvas.drawLine(ox,oy,ox,oy-length*(float)Math.cos(elevation),paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.WHITE);
    canvas.drawText("3D",ox+length+8,oy+length,paint);
    if(!(drawing.maxZ>drawing.minZ+1e-9)){
      paint.setTextSize(11*getResources().getDisplayMetrics().density);
      canvas.drawText("Z 높이 없음: 평면 도면 회전 보기",12, getHeight()-18,paint);
    }
    paint.setStrokeWidth(1.25f*getResources().getDisplayMetrics().density);
    paint.setStyle(Paint.Style.STROKE);
  }
  @Override public boolean onTouchEvent(MotionEvent event) {
    zoom.onTouchEvent(event);
    switch(event.getActionMasked()){
      case MotionEvent.ACTION_DOWN:
        lastX=event.getX();lastY=event.getY();scaling=false;return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        scaling=true;return true;
      case MotionEvent.ACTION_MOVE:
        if(event.getPointerCount()==1&&!zoom.isInProgress()&&!scaling){
          float dx=event.getX()-lastX,dy=event.getY()-lastY;
          yaw+=dx*.008;
          elevation=Math.max(.08,Math.min(Math.PI*.94,elevation+dy*.008));
          invalidate();
        }
        lastX=event.getX();lastY=event.getY();return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        scaling=false;invalidate();return true;
    }
    return true;
  }
}