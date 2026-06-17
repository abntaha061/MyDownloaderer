import yt_dlp
import sys
import traceback

def extract_info(url):
    ydl_opts = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'extract_flat': False,
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if info is None:
                return {"error": "فشل استخراج معلومات الفيديو"}
            
            # Format the output to a safe, minimal dict
            formats = []
            raw_formats = info.get('formats', [])
            for f in raw_formats:
                # Calculate approx size or total size
                filesize = f.get('filesize') or f.get('filesize_approx') or 0
                
                formats.append({
                    'format_id': f.get('format_id', '') or '',
                    'ext': f.get('ext', '') or '',
                    'resolution': f.get('resolution', '') or f.get('format_note', '') or '',
                    'filesize_approx': int(filesize),
                    'acodec': f.get('acodec', '') or '',
                    'vcodec': f.get('vcodec', '') or '',
                })
            
            return {
                "title": info.get('title', 'عنوان غير معروف') or 'عنوان غير معروف',
                "duration": int(info.get('duration') or 0),
                "thumbnail": info.get('thumbnail') or '',
                "formats": formats
            }
    except Exception as e:
        return {"error": str(e)}

def download_video(url, format_id, output_path, ffmpeg_location, callback):
    def hook(d):
        try:
            status = d.get('status', 'downloading')
            downloaded = d.get('downloaded_bytes') or 0
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            speed = d.get('speed') or 0.0
            eta = d.get('eta') or 0
            
            # Call Kotlin/Java callback interface
            callback.onProgress(
                str(status),
                int(downloaded),
                int(total),
                float(speed),
                int(eta)
            )
        except Exception as ex:
            print("Error inside hook callback:", str(ex))

    ydl_opts = {
        'format': format_id,
        'outtmpl': output_path,
        'progress_hooks': [hook],
        'quiet': True,
        'no_warnings': True,
    }
    
    if ffmpeg_location:
        ydl_opts['ffmpeg_location'] = ffmpeg_location
        
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        return {"success": True}
    except Exception as e:
        return {"error": str(e)}
