package kr.civilcad.viewer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;

// Runs the native DWG parser in a separate Android process.
// A native decoder crash must not kill the viewer process.
public final class DwgDecoderService extends Service {
  public static final String ACTION="kr.civilcad.viewer.DWG_DECODE_DONE";
  static { System.loadLibrary("civilcad_native"); }
  public static native void decodeFile(String source,String destination) throws java.io.IOException;

  @Override public IBinder onBind(Intent intent){return null;}
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(intent==null){stopSelf(startId);return START_NOT_STICKY;}
    final String in=intent.getStringExtra("input");
    final String out=intent.getStringExtra("output");
    final String job=intent.getStringExtra("job");
    new Thread(()->{
      Intent result=new Intent(ACTION);
      result.setPackage(getPackageName());
      result.putExtra("job",job);
      try{
        if(in==null||out==null||job==null)throw new IllegalArgumentException("Invalid decode job");
        File input=new File(in),output=new File(out);
        // Only open files inside our app-owned cache directory.
        File directory=getCacheDir().getCanonicalFile();
        if(!directory.equals(input.getCanonicalFile().getParentFile())
            ||!directory.equals(output.getCanonicalFile().getParentFile()))
          throw new SecurityException("Invalid input or output path");
        decodeFile(in,out);
        result.putExtra("output",out);
      }catch(Throwable error){
        result.putExtra("error",error.getMessage()==null?"DWG 해석 작업 오류":error.getMessage());
        if(out!=null)new File(out).delete();
      }finally{
        if(in!=null)new File(in).delete();
        sendBroadcast(result);
        stopSelf(startId);
      }
    },"CivilCAD-DwgDecoder").start();
    return START_NOT_STICKY;
  }
}
