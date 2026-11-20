use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use jni::JNIEnv;
use std::io::Cursor;

#[no_mangle]
pub extern "system" fn Java_kr_civilcad_viewer_MainActivity_decodeDwg(
    mut env: JNIEnv, _class: JClass, bytes: JByteArray,
) -> jbyteArray {
    let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Vec<u8>, String> {
        let dwg = env.convert_byte_array(bytes).map_err(|e| e.to_string())?;
        if dwg.len() > 64 * 1024 * 1024 { return Err("64MB를 넘는 DWG 파일은 지원하지 않습니다.".into()); }
        if !dwg.starts_with(b"AC10") { return Err("올바른 DWG 형식이 아닙니다.".into()); }
        let outcome = acadrust::DwgReader::from_stream_with_options(
            Cursor::new(&dwg), acadrust::DwgReadOptions::failsafe()
        ).read_with_stats().map_err(|e| format!("DWG 읽기 오류: {e}"))?;
        if !outcome.stats.record_stream_read { return Err("DWG 내부 객체를 읽을 수 없습니다.".into()); }
        let mut dxf = Vec::new();
        acadrust::DxfWriter::new(&outcome.document)
            .write_to_writer(&mut dxf).map_err(|e| format!("도면 해석 오류: {e}"))?;
        if dxf.len() > 192 * 1024 * 1024 { return Err("도면 데이터가 너무 큽니다.".into()); }
        Ok(dxf)
    }));
    match run {
        Ok(Ok(dxf)) => match env.byte_array_from_slice(&dxf) {
            Ok(out) => out.into_raw(),
            Err(e) => { let _=env.throw_new("java/io/IOException",e.to_string()); std::ptr::null_mut() }
        },
        Ok(Err(err)) => { let _=env.throw_new("java/io/IOException",err); std::ptr::null_mut() },
        Err(_) => { let _=env.throw_new("java/io/IOException","DWG 해석 중 오류가 발생했습니다."); std::ptr::null_mut() }
    }
}
