import yt_dlp
import sys
import traceback
import re
import os

class YtDlpLogger:
    def __init__(self, callback):
        self.callback = callback
    
    def debug(self, msg):
        if self.callback:
            try:
                self.callback.log("DEBUG", msg)
            except Exception:
                pass

    def info(self, msg):
        if self.callback:
            try:
                self.callback.log("INFO", msg)
            except Exception:
                pass

    def warning(self, msg):
        if self.callback:
            try:
                self.callback.log("WARNING", msg)
            except Exception:
                pass

    def error(self, msg):
        if self.callback:
            try:
                self.callback.log("ERROR", msg)
            except Exception:
                pass

def convert_vtt_to_srt(vtt_path):
    try:
        if not os.path.exists(vtt_path):
            return {"error": f"ملف الترجمة VTT غير موجود في المسار: {vtt_path}"}
        
        with open(vtt_path, 'r', encoding='utf-8') as f:
            content = f.read()

        # Split content into lines
        lines = content.split('\n')
        
        # WEBVTT format usually starts with "WEBVTT".
        # We need to strip the header and find where cue blocks begin.
        header_passed = False
        block_lines = []
        for line in lines:
            line_str = line.strip()
            if not header_passed:
                if line_str == 'WEBVTT' or line_str.startswith('NOTE') or line_str.startswith('Kind:') or line_str.startswith('Language:'):
                    continue
                elif line_str == '':
                    header_passed = True
                continue
            block_lines.append(line)

        blocks_text = '\n'.join(block_lines)
        
        # Match timecode patterns like 00:00:20.123 --> 00:00:24.456
        timecode_pattern = re.compile(r'(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})')
        
        raw_blocks = re.split(r'\n\s*\n', blocks_text)
        srt_blocks = []
        block_counter = 1
        
        for r_block in raw_blocks:
            r_block = r_block.strip()
            if not r_block:
                continue
            
            lines_in_block = r_block.split('\n')
            timecode_line_index = -1
            match = None
            
            for idx, line in enumerate(lines_in_block):
                m = timecode_pattern.search(line)
                if m:
                    timecode_line_index = idx
                    match = m
                    break
            
            if timecode_line_index == -1:
                continue
                
            start_t = match.group(1)
            end_t = match.group(2)
            
            def format_time(t_str):
                if t_str.count(':') == 1:
                    t_str = "00:" + t_str
                return t_str.replace('.', ',')
                
            srt_start_t = format_time(start_t)
            srt_end_t = format_time(end_t)
            
            text_lines = []
            for text_line in lines_in_block[timecode_line_index + 1:]:
                # Clean up html-like styling tags (e.g., <c>...</c>)
                clean_text = re.sub(r'<[^>]+>', '', text_line).strip()
                if clean_text:
                    text_lines.append(clean_text)
                    
            if not text_lines:
                continue
                
            srt_block = f"{block_counter}\n{srt_start_t} --> {srt_end_t}\n" + "\n".join(text_lines)
            srt_blocks.append(srt_block)
            block_counter += 1

        srt_content = "\n\n".join(srt_blocks) + "\n"
        srt_path = vtt_path.rsplit('.', 1)[0] + '.srt'
        
        with open(srt_path, 'w', encoding='utf-8') as f:
            f.write(srt_content)
            
        try:
            os.remove(vtt_path)
        except OSError:
            pass
            
        return {"success": True, "srt_path": srt_path}
    except Exception as e:
        return {"error": f"فشل تحويل srt: {str(e)}"}

def extract_info(url, logger_callback=None, cookie_file=None):
    ydl_opts = {
        'skip_download': True,
        'extract_flat': False,
    }
    if cookie_file:
        ydl_opts['cookiefile'] = cookie_file

    if logger_callback:
        ydl_opts['logger'] = YtDlpLogger(logger_callback)
    else:
        ydl_opts['quiet'] = True
        ydl_opts['no_warnings'] = True
        
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if info is None:
                return {"error": "فشل استخراج معلومات الفيديو"}
            
            if info.get('_type') == 'playlist':
                entries = []
                for entry in info.get('entries', []):
                    if not entry:
                        continue
                    thumb = entry.get('thumbnail') or ''
                    if not thumb and entry.get('thumbnails'):
                        thumb = entry.get('thumbnails')[0].get('url') or ''
                    entries.append({
                        'title': entry.get('title', 'فيديو غير معروف') or 'فيديو غير معروف',
                        'url': entry.get('webpage_url') or entry.get('url') or f"https://www.youtube.com/watch?v={entry.get('id')}",
                        'duration': int(entry.get('duration') or 0),
                        'thumbnail': thumb
                    })
                return {
                    "_type": "playlist",
                    "title": info.get('title', 'قائمة تشغيل غير معروفة') or 'قائمة تشغيل غير معروفة',
                    "entries": entries
                }
            
            # Format formats to a clean Kotlin list
            formats = []
            raw_formats = info.get('formats', [])
            for f in raw_formats:
                filesize = f.get('filesize') or f.get('filesize_approx') or 0
                formats.append({
                    'format_id': f.get('format_id', '') or '',
                    'ext': f.get('ext', '') or '',
                    'resolution': f.get('resolution', '') or f.get('format_note', '') or '',
                    'filesize_approx': int(filesize),
                    'acodec': f.get('acodec', '') or '',
                    'vcodec': f.get('vcodec', '') or '',
                })
            
            # Extract Subtitles & Automatic Captions
            subtitles_map = {}
            if 'subtitles' in info and info['subtitles']:
                for lang_code, s_list in info['subtitles'].items():
                    name = lang_code
                    if s_list and isinstance(s_list, list) and len(s_list) > 0:
                        name = s_list[0].get('name') or lang_code
                    subtitles_map[lang_code] = {"name": name, "type": "manual"}
                    
            if 'automatic_captions' in info and info['automatic_captions']:
                for lang_code, s_list in info['automatic_captions'].items():
                    if lang_code not in subtitles_map:
                        name = lang_code
                        if s_list and isinstance(s_list, list) and len(s_list) > 0:
                            name = s_list[0].get('name') or lang_code
                        subtitles_map[lang_code] = {"name": name, "type": "auto"}
                        
            subtitles_list = []
            for code, data in subtitles_map.items():
                subtitles_list.append({
                    'code': code,
                    'name': data['name'],
                    'type': data['type']
                })
            
            return {
                "title": info.get('title', 'عنوان غير معروف') or 'عنوان غير معروف',
                "duration": int(info.get('duration') or 0),
                "thumbnail": info.get('thumbnail') or '',
                "formats": formats,
                "subtitles": subtitles_list
            }
    except Exception as e:
        return {"error": str(e)}

def download_video(url, format_id, output_path, ffmpeg_location, subtitle_lang, sponsorblock_action, sponsorblock_categories, callback, logger_callback=None, cookie_file=None):
    def hook(d):
        try:
            status = d.get('status', 'downloading')
            downloaded = d.get('downloaded_bytes') or 0
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            speed = d.get('speed') or 0.0
            eta = d.get('eta') or 0
            
            callback.onProgress(
                str(status),
                int(downloaded),
                int(total),
                float(speed),
                int(eta)
            )
        except Exception as ex:
            print("Error inside progress hook:", str(ex))

    # Output directory and base name
    ydl_opts = {
        'format': format_id,
        'outtmpl': output_path,
        'progress_hooks': [hook],
        'concurrent_fragment_downloads': 4,
    }
    
    if cookie_file:
        ydl_opts['cookiefile'] = cookie_file
    
    if logger_callback:
        ydl_opts['logger'] = YtDlpLogger(logger_callback)
    else:
        ydl_opts['quiet'] = True
        ydl_opts['no_warnings'] = True
    
    if ffmpeg_location:
        ydl_opts['ffmpeg_location'] = ffmpeg_location
        
    # Subtitles downloads setup
    if subtitle_lang:
        ydl_opts['writesubtitles'] = True
        ydl_opts['writeautomaticsub'] = True
        ydl_opts['subtitleslangs'] = [subtitle_lang]
        ydl_opts['subtitlesformat'] = 'vtt'

    # SponsorBlock setup
    # sponsorblock_categories is a list of strings
    if sponsorblock_action == 'mark' and sponsorblock_categories:
        ydl_opts['sponsorblock_mark'] = list(sponsorblock_categories)
    elif sponsorblock_action == 'remove' and sponsorblock_categories:
        ydl_opts['sponsorblock_remove'] = list(sponsorblock_categories)

    if ffmpeg_location:
        ydl_opts['writethumbnail'] = True
        ydl_opts['postprocessors'] = ydl_opts.get('postprocessors', []) + [
            {'key': 'FFmpegThumbnailsConvertor', 'format': 'jpg'},
            {'key': 'EmbedThumbnail'}
        ]

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
            
        # Post-download conversion of VTT to SRT.
        # Find VTT file. When download template has fixed folder:
        # If output_path is like "/path/to/vid.mp4", subtitle can be "/path/to/vid.ar.vtt" or "/path/to/vid.en.vtt" etc.
        if subtitle_lang:
            # Let's check both possibilities
            base_path, _ = os.path.splitext(output_path)
            possible_vtt = f"{base_path}.{subtitle_lang}.vtt"
            if os.path.exists(possible_vtt):
                convert_vtt_to_srt(possible_vtt)
            else:
                # Also do a quick scan of the directory of output_path
                dir_name = os.path.dirname(output_path)
                if os.path.exists(dir_name):
                    for file in os.listdir(dir_name):
                        if file.endswith('.vtt') and subtitle_lang in file:
                            convert_vtt_to_srt(os.path.join(dir_name, file))
                            
        return {"success": True}
    except Exception as e:
        return {"error": str(e)}
