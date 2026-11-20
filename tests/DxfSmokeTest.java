package kr.civilcad.viewer;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class DxfSmokeTest {
  static final StringBuilder DXF=new StringBuilder();
  static void p(int code,Object value){DXF.append(code).append('\n').append(value).append('\n');}
  static void section(String name){p(0,"SECTION");p(2,name);}
  static void done(){p(0,"ENDSEC");}
  static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
  public static void main(String[] args) throws Exception {
    section("HEADER");
    p(9,"$INSUNITS");p(70,6);
    done();
    section("BLOCKS");
    p(0,"BLOCK");p(2,"LOT");p(10,0);p(20,0);
    p(0,"LWPOLYLINE");p(8,"BOUNDARY");p(90,4);p(70,1);
    p(10,0);p(20,0);p(10,10);p(20,0);
    p(10,10);p(20,5);p(10,0);p(20,5);
    p(0,"TEXT");p(8,"TEXTS");p(10,1);p(20,1);p(40,1);p(1,"지적 도면");
    p(0,"ENDBLK");
    done();
    section("ENTITIES");
    p(0,"INSERT");p(2,"LOT");p(8,"SITE");p(10,100);p(20,200);p(50,90);
    p(0,"MTEXT");p(8,"TEXTS");p(10,110);p(20,210);p(40,1);p(1,"한글\\P측량");
    p(0,"ATTRIB");p(8,"TEXTS");p(10,115);p(20,215);p(40,1);p(1,"속성");
    done();p(0,"EOF");
    Dxf.Drawing d=Dxf.parse(DXF.toString().getBytes(StandardCharsets.UTF_8));
    require(d.unit==6,"meter unit");
    require(d.lines.size()==4,"block and closed polyline lines: "+d.lines.size());
    require(d.polygons.size()==1,"block closed polygon");
    require(d.inserts==1,"insert count");
    require(d.texts.size()==3,"TEXT, MTEXT, ATTRIB loaded");
    require(Math.abs(d.polygons.get(0).area-50.0)<0.00001,"rotated block polygon area");
    require(Math.abs(Dxf.Polygon.area(Arrays.asList(new double[]{0,0},new double[]{10,0},new double[]{10,5}))-25.0)<1e-8,"shoelace triangle");
    require(d.findPolygon(97.5,205,0.1,Collections.emptySet())!=null,"pick polygon interior");
    require(d.texts.stream().anyMatch(t->t.text.contains("측량")),"multiline MTEXT");
    require(d.texts.stream().anyMatch(t->t.text.contains("지적 도면")),"Korean TEXT");
    require(d.area(50).contains("50.000 m²"),"area unit formatting");
    System.out.println("DXF smoke test PASS - block insert, text, MTEXT, ATTRIB, closed polygon and area");
  }
}
