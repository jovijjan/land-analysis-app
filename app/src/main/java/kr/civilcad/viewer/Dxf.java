package kr.civilcad.viewer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

final class Dxf {
  static final int MAX_LINES = 600000;
  static final int MAX_TEXTS = 60000;
  static final int MAX_POLYGONS = 30000;
  static final int MAX_INSERTS = 80000;
  static final int MAX_RECURSION = 10;
  static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\U\\+([0-9A-Fa-f]{4,8})");
  static final Pattern MTEXT_STYLE = Pattern.compile("\\\\[CcFfHhWwTtQqAa][^;]*;");

  static final class Line {
    final double x1,y1,z1,x2,y2,z2; final String layer;
    Line(double a,double b,double c,double d,String l){this(a,b,0,c,d,0,l);}
    Line(double a,double b,double az,double c,double d,double bz,String l){
      x1=a;y1=b;z1=az;x2=c;y2=d;z2=bz;layer=l;
    }
  }
  static final class Label {
    final double x,y,size,angle; final String text,layer;
    Label(double a,double b,double h,double r,String v,String l){x=a;y=b;size=h;angle=r;text=v;layer=l;}
  }
  static final class Polygon {
    final ArrayList<double[]> points; final String layer;
    final double area;
    Polygon(ArrayList<double[]> points,String layer) {
      this.points=points; this.layer=layer; this.area=area(points);
    }
    static double area(List<double[]> points) {
      int n=points.size(); if(n<3)return 0;
      double x0=points.get(0)[0], y0=points.get(0)[1], twice=0;
      for(int i=1;i<n-1;i++){
        double[] a=points.get(i),b=points.get(i+1);
        twice+=(a[0]-x0)*(b[1]-y0)-(b[0]-x0)*(a[1]-y0);
      }
      return Math.abs(twice)*.5;
    }
    boolean inside(double x,double y) {
      boolean result=false;
      int n=points.size();
      for(int i=0,j=n-1;i<n;j=i++) {
        double[] a=points.get(i), b=points.get(j);
        if((a[1]>y)!=(b[1]>y) && x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])result=!result;
      }
      return result;
    }
    boolean nearEdge(double x,double y,double worldTolerance) {
      double sq=worldTolerance*worldTolerance;
      for(int i=0,n=points.size();i<n;i++){
        double[] a=points.get(i),b=points.get((i+1)%n);
        double dx=b[0]-a[0],dy=b[1]-a[1];
        double denom=dx*dx+dy*dy;
        double t=denom<1e-12?0:Math.max(0,Math.min(1,((x-a[0])*dx+(y-a[1])*dy)/denom));
        double ex=x-a[0]-t*dx,ey=y-a[1]-t*dy;
        if(ex*ex+ey*ey<=sq)return true;
      }
      return false;
    }
  }
  static final class Drawing {
    final ArrayList<Line> lines=new ArrayList<>();
    final ArrayList<Label> texts=new ArrayList<>();
    final ArrayList<Polygon> polygons=new ArrayList<>();
    final TreeSet<String> layers=new TreeSet<>();
    SpatialIndex index;
    void prepareIndex(){index=new SpatialIndex(this);}
    double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,
           maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
    double minZ=Double.POSITIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
    int unit=0; int unitOverride=-1; int inserts=0, unsupported=0;
    void bounds(double x,double y) {
      if(!Double.isFinite(x)||!Double.isFinite(y))return;
      minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
    }
    void line(double x1,double y1,double x2,double y2,String layer) {
      line3d(x1,y1,0,x2,y2,0,layer);
    }
    void line3d(double x1,double y1,double z1,double x2,double y2,double z2,String layer) {
      if(!Double.isFinite(x1)||!Double.isFinite(y1)||!Double.isFinite(z1)
        ||!Double.isFinite(x2)||!Double.isFinite(y2)||!Double.isFinite(z2))return;
      if(lines.size()>=MAX_LINES)throw new IllegalArgumentException("선 요소가 너무 많습니다.");
      lines.add(new Line(x1,y1,z1,x2,y2,z2,layer));layers.add(layer);
      bounds(x1,y1);bounds(x2,y2);
      minZ=Math.min(minZ,Math.min(z1,z2));maxZ=Math.max(maxZ,Math.max(z1,z2));
    }
    void label(double x,double y,double h,double a,String text,String layer) {
      if(text==null||text.isEmpty()||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(h)||h<=0)return;
      if(texts.size()>=MAX_TEXTS)throw new IllegalArgumentException("문자 요소가 너무 많습니다.");
      texts.add(new Label(x,y,h,a,text,layer));layers.add(layer);
      bounds(x,y); bounds(x+h*Math.min(80,text.length())*.65,y+h);
    }
    void polygon(ArrayList<double[]> points,String layer) {
      if(points.size()<3||polygons.size()>=MAX_POLYGONS)return;
      polygons.add(new Polygon(points,layer));
    }
    int chosenUnit(){return unitOverride<0?unit:unitOverride;}
    double metresPerUnit(){
      switch(chosenUnit()){case 1:return .0254;case 2:return .3048;case 4:return .001;case 5:return .01;
        case 6:return 1;case 7:return 1000;case 10:return .9144;default:return Double.NaN;}
    }
    String units(){
      switch(chosenUnit()){case 1:return "inch";case 2:return "ft";case 4:return "mm";case 5:return "cm";
        case 6:return "m";case 7:return "km";case 10:return "yard";default:return "도면 단위";}
    }
    String distance(double value){
      double factor=metresPerUnit();
      return Double.isFinite(factor)?String.format(Locale.KOREA,"%.3f m",value*factor):
        String.format(Locale.KOREA,"%.3f 도면단위",value);
    }
    String area(double value){
      double factor=metresPerUnit();
      return Double.isFinite(factor)?String.format(Locale.KOREA,"%.3f m²",value*factor*factor):
        String.format(Locale.KOREA,"%.3f 도면단위²",value);
    }
    Polygon findPolygon(double x,double y,double tolerance,Set<String> hidden){
      Polygon candidate=null;double best=Double.POSITIVE_INFINITY;
      for(Polygon poly:polygons){
        if(hidden.contains(poly.layer)||poly.area<=0)continue;
        if((poly.inside(x,y)||poly.nearEdge(x,y,tolerance))&&poly.area<best){
          best=poly.area;candidate=poly;
        }
      }
      return candidate;
    }
    double[] snap(double x,double y,double tolerance,Set<String> hidden){
      double best=tolerance*tolerance;
      double[] found=null;
      IntBag candidates=index==null?null:index.query(x-tolerance,y-tolerance,x+tolerance,y+tolerance);
      int size=candidates==null?lines.size():candidates.size;
      for(int i=0;i<size;i++){
        Line line=lines.get(candidates==null?i:candidates.values[i]);
        if(hidden.contains(line.layer))continue;
        double dx=line.x1-x,dy=line.y1-y,r=dx*dx+dy*dy;
        if(r<best){best=r;found=new double[]{line.x1,line.y1};}
        dx=line.x2-x;dy=line.y2-y;r=dx*dx+dy*dy;
        if(r<best){best=r;found=new double[]{line.x2,line.y2};}
      }
      return found==null?new double[]{x,y}:found;
    }
  }

  static final class IntBag {
    int[] values=new int[16];
    int size=0;
    void add(int n){
      if(size==values.length)values=Arrays.copyOf(values,values.length*2);
      values[size++]=n;
    }
  }
  // Spatial tiles avoid scanning the entire drawing for every pan/zoom frame.
  static final class SpatialIndex {
    static final int GRID=48;
    final IntBag[] cells=new IntBag[GRID*GRID];
    final IntBag longLines=new IntBag();
    final double minX,minY,stepX,stepY;
    final int[] seen;
    int stamp;
    final IntBag visibleScratch=new IntBag();
    SpatialIndex(Drawing drawing){
      minX=drawing.minX;minY=drawing.minY;
      stepX=Math.max(.0000001,(drawing.maxX-drawing.minX)/GRID);
      stepY=Math.max(.0000001,(drawing.maxY-drawing.minY)/GRID);
      seen=new int[drawing.lines.size()];
      for(int i=0;i<drawing.lines.size();i++){
        Line line=drawing.lines.get(i);
        int x0=cellX(Math.min(line.x1,line.x2)),x1=cellX(Math.max(line.x1,line.x2));
        int y0=cellY(Math.min(line.y1,line.y2)),y1=cellY(Math.max(line.y1,line.y2));
        if((x1-x0+1)*(y1-y0+1)>32){longLines.add(i);continue;}
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){
          int at=y*GRID+x;
          if(cells[at]==null)cells[at]=new IntBag();
          cells[at].add(i);
        }
      }
    }
    int cellX(double value){return Math.max(0,Math.min(GRID-1,(int)Math.floor((value-minX)/stepX)));}
    int cellY(double value){return Math.max(0,Math.min(GRID-1,(int)Math.floor((value-minY)/stepY)));}
    IntBag query(double x0,double y0,double x1,double y1){
      IntBag result=visibleScratch;
      result.size=0;
      if(stamp==Integer.MAX_VALUE){Arrays.fill(seen,0);stamp=0;}
      int tag=++stamp;
      int left=cellX(Math.min(x0,x1)),right=cellX(Math.max(x0,x1));
      int bottom=cellY(Math.min(y0,y1)),top=cellY(Math.max(y0,y1));
      for(int y=bottom;y<=top;y++)for(int x=left;x<=right;x++){
        IntBag cell=cells[y*GRID+x];if(cell==null)continue;
        for(int i=0;i<cell.size;i++){int id=cell.values[i];if(seen[id]!=tag){seen[id]=tag;result.add(id);}}
      }
      for(int i=0;i<longLines.size;i++){int id=longLines.values[i];if(seen[id]!=tag){seen[id]=tag;result.add(id);}}
      return result;
    }
  }
  static final class Pair {
    final int key;final String value;
    Pair(int k,String v){key=k;value=v;}
  }
  static final class Entity {
    final String type;final ArrayList<Pair> vals=new ArrayList<>();
    Entity(String t){type=t;}
    String str(int code,String fallback){
      for(Pair p:vals)if(p.key==code)return p.value;
      return fallback;
    }
    double num(int code,double fallback){
      try{return Double.parseDouble(str(code,""));}catch(Exception e){return fallback;}
    }
    int integer(int code,int fallback){
      try{return Integer.parseInt(str(code,""));}catch(Exception e){return fallback;}
    }
    String layer(){return str(8,"0");}
    String fullText(){
      if(!type.equals("MTEXT"))return cleanText(str(1,""));
      StringBuilder result=new StringBuilder();
      for(Pair p:vals)if(p.key==3||p.key==1)result.append(p.value);
      return cleanText(result.toString());
    }
  }
  static final class Block {
    final String name; final double baseX,baseY;final ArrayList<Entity> entries=new ArrayList<>();
    Block(Entity h){name=h.str(2,h.str(3,""));baseX=h.num(10,0);baseY=h.num(20,0);}
  }
  static final class Transform {
    final double a,b,c,d,x,y,z,sz;
    Transform(double a,double b,double c,double d,double x,double y){
      this(a,b,c,d,x,y,0,1);
    }
    Transform(double a,double b,double c,double d,double x,double y,double z,double sz){
      this.a=a;this.b=b;this.c=c;this.d=d;this.x=x;this.y=y;this.z=z;this.sz=sz;
    }
    static Transform identity(){return new Transform(1,0,0,1,0,0);}
    double[] point(double px,double py){return new double[]{a*px+c*py+x,b*px+d*py+y};}
    double elevation(double local){return z+sz*local;}
    Transform compose(Transform local){
      return new Transform(a*local.a+c*local.b,b*local.a+d*local.b,
        a*local.c+c*local.d,b*local.c+d*local.d,
        a*local.x+c*local.y+x,b*local.x+d*local.y+y,elevation(local.z),sz*local.sz);
    }
  }
  static Pair next(BufferedReader in)throws IOException {
    String k=in.readLine(),v=in.readLine();if(k==null||v==null)return null;
    try{return new Pair(Integer.parseInt(k.trim()),v.trim());}
    catch(Exception e){throw new IOException("잘못된 DXF 그룹 코드");}
  }
  static Charset sourceCharset(byte[] bytes) {
    try{
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
      return StandardCharsets.UTF_8;
    }catch(Exception ignored){}
    // Older Korean drawings commonly contain CP949 / ANSI_949 strings.
    return Charset.forName("MS949");
  }
  static String cleanText(String raw) {
    if(raw==null)return "";
    Matcher matcher=UNICODE_ESCAPE.matcher(raw);
    StringBuffer out=new StringBuffer();
    while(matcher.find()){
      String replacement="";
      try{replacement=new String(Character.toChars(Integer.parseInt(matcher.group(1),16)));}
      catch(Exception ignored){}
      matcher.appendReplacement(out,Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    String value=out.toString().replace("\\P","\n").replace("\\~"," ")
      .replace("%%d","°").replace("%%D","°").replace("%%p","±").replace("%%P","±")
      .replace("%%c","Ø").replace("%%C","Ø");
    value=MTEXT_STYLE.matcher(value).replaceAll("");
    return value.replace("\\L","").replace("\\l","").replace("\\O","").replace("\\o","")
      .replace("{","").replace("}","");
  }

  static Drawing parse(byte[] bytes)throws IOException {
    if(bytes.length>192*1024*1024)throw new IOException("도면 데이터가 너무 큽니다.");
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(
        new ByteArrayInputStream(bytes),sourceCharset(bytes)),65536)){
      return parseReader(reader);
    }
  }
  // Stream the DXF converted from DWG, rather than copying the entire DXF into Java heap.
  static Drawing parse(File file)throws IOException {
    if(file.length()>220L*1024*1024)throw new IOException("변환된 도면 데이터가 너무 큽니다.");
    byte[] header=new byte[(int)Math.min(file.length(),128*1024)];
    int got=0;
    try(FileInputStream input=new FileInputStream(file)){
      while(got<header.length){
        int n=input.read(header,got,header.length-got);
        if(n<0)break;
        got+=n;
      }
    }
    Charset charset=sourceCharset(Arrays.copyOf(header,got));
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(
        new FileInputStream(file),charset),65536)){
      return parseReader(reader);
    }
  }
  static boolean retain(int key) {
    switch(key){
      case 1: case 2: case 3: case 8: case 10: case 11:
      case 20: case 21: case 40: case 41: case 42:
      case 44: case 45: case 50: case 51: case 70:
      case 71: case 72: case 73: case 90:
      case 30: case 31: case 32: case 33: case 38: case 39:return true;
      default:return false;
    }
  }
  static Drawing parseReader(BufferedReader reader)throws IOException {
    Drawing d=new Drawing();
    Map<String,Block> blocks=new HashMap<>();
    String section="";
    boolean sectionPending=false,unitPending=false;
    Entity active=null,modelPolyline=null;
    Block currentBlock=null;
    ArrayList<double[]> vertices=new ArrayList<>();
    final Transform identity=Transform.identity();
    final HashSet<String> emptyStack=new HashSet<>();
    int groups=0;
    Pair pair;
    while((pair=next(reader))!=null){
      if(++groups>12000000)throw new IOException("DXF 데이터가 너무 큽니다.");
      if(pair.key==0){
        if(active!=null){
          if(section.equals("BLOCKS")&&active.type.equals("BLOCK"))currentBlock=new Block(active);
          else if(section.equals("BLOCKS")&&active.type.equals("ENDBLK")){
            if(currentBlock!=null&&!currentBlock.name.isEmpty())
              blocks.put(currentBlock.name.toUpperCase(Locale.ROOT),currentBlock);
            currentBlock=null;
          }else if(section.equals("BLOCKS")&&currentBlock!=null){
            currentBlock.entries.add(active);
          }else if(section.equals("ENTITIES")){
            if(active.type.equals("POLYLINE")){
              modelPolyline=active;vertices.clear();
            }else if(active.type.equals("VERTEX")&&modelPolyline!=null){
              vertices.add(new double[]{active.num(10,0),active.num(20,0),active.num(42,0),active.num(30,0)});
            }else if(active.type.equals("SEQEND")&&modelPolyline!=null){
              int flag=modelPolyline.integer(70,0);
              if((flag&8)!=0){
                int count=vertices.size(),edges=(flag&1)!=0?count:count-1;
                for(int i=0;i<edges;i++){
                  double[] p=vertices.get(i),q=vertices.get((i+1)%count);
                  d.line3d(p[0],p[1],p[3],q[0],q[1],q[3],modelPolyline.layer());
                }
              }else{
                Entity poly=new Entity("LWPOLYLINE");
                for(double[] point:vertices){
                  poly.vals.add(new Pair(10,Double.toString(point[0])));
                  poly.vals.add(new Pair(20,Double.toString(point[1])));
                  if(point[2]!=0)poly.vals.add(new Pair(42,Double.toString(point[2])));
                }
                poly.vals.add(new Pair(8,modelPolyline.layer()));
                poly.vals.add(new Pair(70,Integer.toString(flag&1)));
                poly.vals.add(new Pair(38,Double.toString(modelPolyline.num(30,0))));
                flatten(d,Collections.singletonList(poly),blocks,identity,"0",0,emptyStack);
              }
              modelPolyline=null;vertices.clear();
            }else if(!active.type.equals("VERTEX")){
              flatten(d,Collections.singletonList(active),blocks,identity,"0",0,emptyStack);
            }
          }
          active=null;
        }
        if(pair.value.equals("SECTION")){sectionPending=true;section="";}
        else if(pair.value.equals("ENDSEC")){section="";sectionPending=false;}
        else if(pair.value.equals("EOF"))section="";
        else if(section.equals("ENTITIES")||section.equals("BLOCKS"))active=new Entity(pair.value);
      }else if(sectionPending&&pair.key==2){
        section=pair.value;sectionPending=false;
      }else{
        if(pair.key==9)unitPending=pair.value.equals("$INSUNITS");
        else if(unitPending&&pair.key==70){
          try{d.unit=Integer.parseInt(pair.value);}catch(Exception ignored){}
          unitPending=false;
        }
        if(active!=null&&retain(pair.key))active.vals.add(pair);
      }
    }
    if(active!=null&&section.equals("ENTITIES"))
      flatten(d,Collections.singletonList(active),blocks,identity,"0",0,emptyStack);
    if(d.lines.isEmpty()&&d.texts.isEmpty())throw new IOException("표시 가능한 선 또는 문자가 없습니다.");
    d.prepareIndex();
    return d;
  }
  static String layer(Entity e,String inherited){
    String layer=e.layer();
    return layer.equals("0")?inherited:layer;
  }
  static void flatten(Drawing d,List<Entity> entries,Map<String,Block> blocks,
                       Transform transform,String inherited,int depth,Set<String> stack){
    if(depth>MAX_RECURSION)return;
    for(int i=0;i<entries.size();i++){
      Entity e=entries.get(i);
      String l=layer(e,inherited);
      if(e.type.equals("POLYLINE")){
        ArrayList<double[]> pts=new ArrayList<>();
        int flags=e.integer(70,0);
        int j=i+1;
        while(j<entries.size()&&entries.get(j).type.equals("VERTEX")){
          Entity v=entries.get(j);
          pts.add(new double[]{v.num(10,0),v.num(20,0),v.num(42,0),v.num(30,0)});
          j++;
        }
        if(j<entries.size()&&entries.get(j).type.equals("SEQEND"))j++;
        if((flags&8)!=0){
          int n=pts.size(),edges=(flags&1)!=0?n:n-1;
          for(int z=0;z<edges;z++){
            double[] p=pts.get(z),q=pts.get((z+1)%n);
            double[] a=transform.point(p[0],p[1]),b=transform.point(q[0],q[1]);
            d.line3d(a[0],a[1],transform.elevation(p[3]),
                b[0],b[1],transform.elevation(q[3]),l);
          }
        }else appendPoly(d,pts,l,(flags&1)!=0,transform);
        i=j-1;continue;
      }
      if(e.type.equals("INSERT")){
        if(d.inserts++>MAX_INSERTS)throw new IllegalArgumentException("블록이 너무 많습니다.");
        String name=e.str(2,"").toUpperCase(Locale.ROOT);
        Block block=blocks.get(name);
        if(block==null||stack.contains(name))continue;
        double angle=Math.toRadians(e.num(50,0)),sin=Math.sin(angle),cos=Math.cos(angle);
        double sx=e.num(41,1),sy=e.num(42,1);
        if(!Double.isFinite(sx)||!Double.isFinite(sy)||sx==0||sy==0)continue;
        int cols=Math.max(1,Math.min(100,e.integer(70,1)));
        int rows=Math.max(1,Math.min(100,e.integer(71,1)));
        double a=cos*sx,b=sin*sx,c=-sin*sy,f=cos*sy;
        HashSet<String> nextStack=new HashSet<>(stack);nextStack.add(name);
        for(int r=0;r<rows;r++)for(int col=0;col<cols;col++){
          double dx=e.num(44,0)*col-block.baseX,dy=e.num(45,0)*r-block.baseY;
          Transform t=new Transform(a,b,c,f,e.num(10,0)+a*dx+c*dy,
            e.num(20,0)+b*dx+f*dy,e.num(30,0),e.num(43,1));
          flatten(d,block.entries,blocks,transform.compose(t),l,depth+1,nextStack);
        }
        continue;
      }
      switch(e.type){
        case "LINE":{
          double[] p1=transform.point(e.num(10,0),e.num(20,0)),
                   p2=transform.point(e.num(11,0),e.num(21,0));
          d.line3d(p1[0],p1[1],transform.elevation(e.num(30,0)),
              p2[0],p2[1],transform.elevation(e.num(31,0)),l);break;
        }
        case "LWPOLYLINE":{
          ArrayList<double[]> pts=new ArrayList<>();
          for(Pair g:e.vals){
            try{
              if(g.key==10)pts.add(new double[]{Double.parseDouble(g.value),0,0});
              else if(g.key==20&&!pts.isEmpty())pts.get(pts.size()-1)[1]=Double.parseDouble(g.value);
              else if(g.key==42&&!pts.isEmpty())pts.get(pts.size()-1)[2]=Double.parseDouble(g.value);
            }catch(Exception ignored){}
          }
          appendPoly(d,pts,l,(e.integer(70,0)&1)!=0,transform,e.num(38,0));break;
        }
        case "CIRCLE": case "ARC":{
          double start=0,end=360;
          if(e.type.equals("ARC")){
            start=e.num(50,0);end=e.num(51,0);
            while(end<=start)end+=360;
            if(end-start>360)end=start+360;
          }
          appendArc(d,e.num(10,0),e.num(20,0),e.num(40,0),
                     start,end,l,transform,e.type.equals("CIRCLE"),e.num(30,0));
          break;
        }
        case "3DFACE": {
          double[][] pts=new double[4][3];
          for(int j=0;j<4;j++){
            int xi=10+j, yi=20+j, zi=30+j;
            double[] xy=transform.point(e.num(xi,0),e.num(yi,0));
            pts[j][0]=xy[0];pts[j][1]=xy[1];pts[j][2]=transform.elevation(e.num(zi,0));
          }
          boolean triangle=pts[3][0]==pts[2][0]&&pts[3][1]==pts[2][1]&&pts[3][2]==pts[2][2];
          int vertices=triangle?3:4;
          for(int j=0;j<vertices;j++){
            double[] first=pts[j],second=pts[(j+1)%vertices];
            d.line3d(first[0],first[1],first[2],second[0],second[1],second[2],l);
          }
          break;
        }
        case "TEXT": case "MTEXT": case "ATTRIB": case "ATTDEF":{
          String value=e.fullText();
          if(!value.isEmpty()){
            boolean aligned=!e.type.equals("MTEXT")&&(e.integer(72,0)!=0||e.integer(73,0)!=0);
            double x=e.num(aligned?11:10,e.num(10,0)),y=e.num(aligned?21:20,e.num(20,0));
            double h=e.num(40,2.5);if(h<=0)h=2.5;
            double rotation=e.num(50,0);
            if(e.type.equals("MTEXT")&&e.str(11,"").length()>0){
              rotation=Math.toDegrees(Math.atan2(e.num(21,0),e.num(11,1)));
            }
            double rad=Math.toRadians(rotation);
            double[] p=transform.point(x,y);
            double ux=transform.a*Math.cos(rad)+transform.c*Math.sin(rad),
                   uy=transform.b*Math.cos(rad)+transform.d*Math.sin(rad);
            double size=h*Math.hypot(ux,uy);
            if(Double.isFinite(size)){
              d.label(p[0],p[1],size,Math.atan2(uy,ux),value,l);
            }
          }break;
        }
        default:
          if(!e.type.equals("SEQEND")&&!e.type.equals("VERTEX"))d.unsupported++;
      }
    }
  }
  static void appendArc(Drawing d,double cx,double cy,double r,double start,double end,
                        String layer,Transform t,boolean closed,double elevation){
    if(!Double.isFinite(r)||r<=0||r>1e10)return;
    int count=Math.max(24,Math.min(360,(int)Math.ceil(Math.abs(end-start)/3.0)));
    ArrayList<double[]> pts=new ArrayList<>();
    for(int i=0;i<=count;i++){
      double a=Math.toRadians(start+(end-start)*i/count);
      pts.add(t.point(cx+r*Math.cos(a),cy+r*Math.sin(a)));
    }
    for(int i=1;i<pts.size();i++){
      double[] p=pts.get(i-1),q=pts.get(i);
      d.line3d(p[0],p[1],t.elevation(elevation),q[0],q[1],t.elevation(elevation),layer);
    }
    if(closed){pts.remove(pts.size()-1);d.polygon(pts,layer);}
  }
  static void appendPoly(Drawing d,List<double[]> points,String layer,boolean closed,Transform t){
    appendPoly(d,points,layer,closed,t,0);
  }
  static void appendPoly(Drawing d,List<double[]> points,String layer,boolean closed,Transform t,double elevation){
    if(points.size()<2)return;
    ArrayList<double[]> sampled=new ArrayList<>();
    int n=closed?points.size():points.size()-1;
    for(int i=0;i<n;i++){
      double[] p=points.get(i),q=points.get((i+1)%points.size());
      if(sampled.isEmpty())sampled.add(t.point(p[0],p[1]));
      double bulge=p.length>2?p[2]:0;
      if(Math.abs(bulge)<1e-9) {
        double[] to=t.point(q[0],q[1]),from=sampled.get(sampled.size()-1);
        d.line3d(from[0],from[1],t.elevation(elevation),to[0],to[1],t.elevation(elevation),layer);sampled.add(to);
      }else{
        double dx=q[0]-p[0],dy=q[1]-p[1],ch=Math.hypot(dx,dy);
        if(ch<1e-9)continue;
        double offset=ch*(1-bulge*bulge)/(4*bulge);
        double cx=(p[0]+q[0])/2-dy/ch*offset,cy=(p[1]+q[1])/2+dx/ch*offset;
        double radius=Math.hypot(p[0]-cx,p[1]-cy);
        double start=Math.atan2(p[1]-cy,p[0]-cx),sweep=4*Math.atan(bulge);
        int count=Math.max(8,Math.min(180,(int)Math.ceil(Math.abs(sweep)/Math.toRadians(3))));
        for(int step=1;step<=count;step++){
          double a=start+sweep*step/count;
          double[] to=t.point(cx+radius*Math.cos(a),cy+radius*Math.sin(a));
          double[] from=sampled.get(sampled.size()-1);
          d.line(from[0],from[1],to[0],to[1],layer);sampled.add(to);
        }
      }
    }
    if(closed){
      if(sampled.size()>1) {
        double[] a=sampled.get(0),b=sampled.get(sampled.size()-1);
        if(Math.hypot(a[0]-b[0],a[1]-b[1])<1e-6)sampled.remove(sampled.size()-1);
      }
      d.polygon(sampled,layer);
    }
  }
}
