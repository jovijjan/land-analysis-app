package kr.civilcad.viewer;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
final class Dxf {
  static final class Line {final double x1,y1,x2,y2; final String layer;
    Line(double a,double b,double c,double d,String l){x1=a;y1=b;x2=c;y2=d;layer=l;}}
  static final class Drawing {
    final ArrayList<Line> lines=new ArrayList<>();final TreeSet<String> layers=new TreeSet<>();
    double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;int unit=0;
    void add(double a,double b,double c,double d,String layer){
      if(!Double.isFinite(a)||!Double.isFinite(b)||!Double.isFinite(c)||!Double.isFinite(d))return;
      if(lines.size()>=500000)throw new IllegalArgumentException("선 개수 제한 초과");
      lines.add(new Line(a,b,c,d,layer));layers.add(layer);
      minX=Math.min(minX,Math.min(a,c));maxX=Math.max(maxX,Math.max(a,c));
      minY=Math.min(minY,Math.min(b,d));maxY=Math.max(maxY,Math.max(b,d));
    }
    String units(){switch(unit){case 1:return "inch";case 2:return "ft";case 4:return "mm";case 5:return "cm";case 6:return "m";default:return "도면 단위";}}
  }
  static class Pair {final int key;final String value;Pair(int k,String v){key=k;value=v;}}
  static class Entity {
    final String name;final ArrayList<Pair> vals=new ArrayList<>();
    Entity(String n){name=n;}
    String str(int key,String def){for(Pair p:vals)if(p.key==key)return p.value;return def;}
    double num(int key,double def){try{return Double.parseDouble(str(key,""));}catch(Exception e){return def;}}
    int integer(int key,int def){try{return Integer.parseInt(str(key,""));}catch(Exception e){return def;}}
    String layer(){return str(8,"0");}
  }
  static Pair next(BufferedReader in)throws IOException{
    String code=in.readLine();String v=in.readLine();if(code==null||v==null)return null;
    try{return new Pair(Integer.parseInt(code.trim()),v.trim());}catch(Exception e){return new Pair(-1,v);}
  }
  static Drawing parse(byte[] bytes)throws IOException {
    Drawing out=new Drawing();if(bytes.length>192*1024*1024)throw new IOException("파일 용량 초과");
    BufferedReader in=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8),32768);
    boolean sectionPending=false,ent=false,unitPending=false;
    Entity item=null,poly=null;ArrayList<double[]> vertices=new ArrayList<>();
    Pair p;
    while((p=next(in))!=null){
      if(p.key==0){
        if(item!=null) {
          if(item.name.equals("VERTEX")&&poly!=null)vertices.add(new double[]{item.num(10,0),item.num(20,0),item.num(42,0)});
          else if(item.name.equals("SEQEND")&&poly!=null){poly(out,vertices,poly.layer(),(poly.integer(70,0)&1)!=0);poly=null;vertices.clear();}
          else if(item.name.equals("POLYLINE")){poly=item;vertices.clear();}
          else if(!item.name.equals("VERTEX"))add(out,item);
        }
        item=null;
        if(p.value.equals("SECTION")){sectionPending=true;ent=false;}
        else if(p.value.equals("ENDSEC"))ent=false;
        else if(ent)item=new Entity(p.value);
        continue;
      }
      if(sectionPending&&p.key==2){ent=p.value.equals("ENTITIES");sectionPending=false;continue;}
      if(p.key==9)unitPending=p.value.equals("$INSUNITS");
      else if(unitPending&&p.key==70){try{out.unit=Integer.parseInt(p.value);}catch(Exception ignored){}unitPending=false;}
      if(item!=null)item.vals.add(p);
    }
    if(item!=null) add(out,item);
    if(poly!=null)poly(out,vertices,poly.layer(),(poly.integer(70,0)&1)!=0);
    if(out.lines.isEmpty())throw new IOException("표시 가능한 선 요소가 없습니다 (BLOCK/INSERT 미지원).");
    return out;
  }
  static void add(Drawing d,Entity e){
    String layer=e.layer();
    switch(e.name){
      case "LINE":d.add(e.num(10,0),e.num(20,0),e.num(11,0),e.num(21,0),layer);break;
      case "CIRCLE":arc(d,e.num(10,0),e.num(20,0),e.num(40,0),0,360,layer);break;
      case "ARC":double start=e.num(50,0),end=e.num(51,0);while(end<=start)end+=360;arc(d,e.num(10,0),e.num(20,0),e.num(40,0),start,end,layer);break;
      case "LWPOLYLINE":
        ArrayList<double[]> v=new ArrayList<>();
        for(Pair p:e.vals){
          try{if(p.key==10)v.add(new double[]{Double.parseDouble(p.value),0,0});
          else if(p.key==20&&!v.isEmpty())v.get(v.size()-1)[1]=Double.parseDouble(p.value);
          else if(p.key==42&&!v.isEmpty())v.get(v.size()-1)[2]=Double.parseDouble(p.value);}catch(Exception ignored){}
        }
        poly(d,v,layer,(e.integer(70,0)&1)!=0);break;
    }
  }
  static void poly(Drawing d,ArrayList<double[]> vertices,String l,boolean closed){
    if(vertices.size()<2)return;int n=closed?vertices.size():vertices.size()-1;
    for(int i=0;i<n;i++){
      double[] a=vertices.get(i),b=vertices.get((i+1)%vertices.size());
      double bulge=a[2];if(Math.abs(bulge)<1e-10){d.add(a[0],a[1],b[0],b[1],l);continue;}
      double dx=b[0]-a[0],dy=b[1]-a[1],ch=Math.hypot(dx,dy);
      if(ch<1e-9)continue;
      double offset=ch*(1-bulge*bulge)/(4*bulge);
      double cx=(a[0]+b[0])/2-dy/ch*offset,cy=(a[1]+b[1])/2+dx/ch*offset;
      double r=Math.hypot(a[0]-cx,a[1]-cy),start=Math.atan2(a[1]-cy,a[0]-cx),angle=4*Math.atan(bulge);
      double px=a[0],py=a[1];int count=Math.max(8,Math.min(100,(int)Math.ceil(Math.abs(angle)/.08)));
      for(int k=1;k<=count;k++){
        double t=start+k*angle/count;double x=cx+r*Math.cos(t),y=cy+r*Math.sin(t);
        d.add(px,py,x,y,l);px=x;py=y;
      }
    }
  }
  static void arc(Drawing d,double cx,double cy,double r,double start,double end,String l){
    if(r<=0||r>1e9)return;int n=Math.max(18,Math.min(200,(int)Math.ceil((end-start)/5)));
    double px=cx+r*Math.cos(Math.toRadians(start)),py=cy+r*Math.sin(Math.toRadians(start));
    for(int i=1;i<=n;i++){
      double t=Math.toRadians(start+(end-start)*i/n),x=cx+r*Math.cos(t),y=cy+r*Math.sin(t);
      d.add(px,py,x,y,l);px=x;py=y;
    }
  }
}
