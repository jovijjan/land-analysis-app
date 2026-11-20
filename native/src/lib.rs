use jni::objects::{JClass, JString};
use jni::JNIEnv;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::panic::AssertUnwindSafe;

struct LimitedWriter<W: Write> {
    inner: W,
    written: u64,
    max: u64,
}
impl<W: Write> Write for LimitedWriter<W> {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        if self.written.saturating_add(data.len() as u64) > self.max {
            return Err(std::io::Error::other("DWG에서 변환된 데이터가 메모리 제한을 초과합니다."));
        }
        let count = self.inner.write(data)?;
        self.written += count as u64;
        Ok(count)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}
fn convert_file(from: &str, to: &str) -> Result<(), String> {
    let file = File::open(from).map_err(|e| format!("파일 열기 오류: {e}"))?;
    let len = file.metadata().map_err(|e| e.to_string())?.len();
    if len == 0 || len > 64 * 1024 * 1024 {
        return Err("DWG 파일은 64MB 이하만 지원합니다.".into());
    }
    let mut header = [0; 6];
    {
        use std::io::Read;
        let mut head = file.try_clone().map_err(|e| e.to_string())?;
        head.read_exact(&mut header).map_err(|e| e.to_string())?;
    }
    if !header.starts_with(b"AC10") {
        return Err("유효한 DWG 파일이 아닙니다.".into());
    }
    let outcome = acadrust::DwgReader::from_stream_with_options(
        file,
        acadrust::DwgReadOptions::failsafe(),
    )
    .read_with_stats()
    .map_err(|e| format!("DWG 읽기 오류: {e}"))?;
    if !outcome.stats.record_stream_read {
        return Err("DWG 객체 데이터가 손상되었거나 지원되지 않습니다.".into());
    }
    let destination = File::create(to).map_err(|e| format!("캐시 파일 생성 오류: {e}"))?;
    let mut writer = LimitedWriter {
        inner: BufWriter::new(destination),
        written: 0,
        max: 192 * 1024 * 1024,
    };
    acadrust::DxfWriter::new(&outcome.document)
        .write_to_writer(&mut writer)
        .map_err(|e| format!("DWG 변환 오류: {e}"))?;
    writer.flush().map_err(|e| e.to_string())?;
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_kr_civilcad_viewer_DwgDecoderService_decodeFile(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    target: JString,
) {
    let converted = std::panic::catch_unwind(AssertUnwindSafe(|| -> Result<(), String> {
        let from: String = env.get_string(&source).map_err(|e| e.to_string())?.into();
        let to: String = env.get_string(&target).map_err(|e| e.to_string())?.into();
        convert_file(&from, &to)
    }));
    match converted {
        Ok(Ok(())) => (),
        Ok(Err(err)) => {
            let _ = env.throw_new("java/io/IOException", err);
        }
        Err(_) => {
            let _ = env.throw_new("java/io/IOException", "DWG 해석 중 오류가 발생했습니다.");
        }
    }
}
