#!/usr/bin/env python3
"""Compose genuine emulator recordings with English titles and SubtitleKit narration."""
from __future__ import annotations
import json, shutil, subprocess, sys, zipfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent.parent
WORK=ROOT/'build/media-work'
CAPTURE=WORK/'final-capture'
MEDIA=ROOT/'media'
SHOTS=MEDIA/'screenshots'
VIDEO=MEDIA/'video'
FONT='/System/Library/Fonts/Supplemental/Arial.ttf'
BOLD='/System/Library/Fonts/Supplemental/Arial Bold.ttf'
BG='#0B141D'; WHITE='#EDF3F7'; MUTED='#9FB1BF'; MINT='#62E0BD'; LINE='#29404E'
W,H=1920,1080

def font(size:int,bold:bool=False): return ImageFont.truetype(BOLD if bold else FONT,size)
def wrap(draw,text,fnt,width):
    lines=[]
    for paragraph in text.split('\n'):
        current=''
        for word in paragraph.split():
            candidate=f'{current} {word}'.strip()
            if current and draw.textlength(candidate,font=fnt)>width: lines.append(current); current=word
            else: current=candidate
        lines.append(current)
    return '\n'.join(lines)
def panel(scene:dict,index:int,total:int,path:Path):
    im=Image.new('RGB',(W,H),BG); d=ImageDraw.Draw(im)
    for radius in (240,420,600):
        d.ellipse((1100-radius,1080-radius,1100+radius,1080+radius),outline='#112430',width=2)
    d.rounded_rectangle((100,82,118,100),radius=9,fill=MINT)
    d.text((136,69),'IoT Scanner',font=font(32,True),fill=WHITE)
    d.text((102,130),'ANDROID APP  /  ENGLISH DEMO',font=font(17,True),fill=MUTED)
    d.text((1090,84),f'{index:02d} / {total:02d}',font=font(23),fill=MUTED)
    title=wrap(d,scene['title'],font(68,True),1060)
    d.multiline_text((98,250),title,font=font(68,True),fill=WHITE,spacing=10)
    d.multiline_text((102,460),scene['subtitle'],font=font(31),fill=MUTED,spacing=12)
    pills={
        'discover':['Bluetooth LE','Wi-Fi','On your device'],
        'range':['Nearby','Balanced','Wide','Custom'],
        'alerts':['Signal strength','Signature matches'],
        'baseline':['Record','Monitor','Review'],
        'finder':['Stronger','Steady','Weaker'],
        'report':['CSV reports','No account','No cloud'],
    }.get(scene['id'],['Android 8.0+','v0.1.2'])
    x=102
    for pill in pills:
        width=int(d.textlength(pill,font=font(21,True)))+38
        d.rounded_rectangle((x,585,x+width,634),radius=12,fill='#162B35',outline=LINE,width=1)
        d.text((x+19,598),pill,font=font(21,True),fill=MINT)
        x+=width+13
    notes={
        'discover':('A clearer view of nearby signals.','Explore advertisers and networks without an account or cloud connection.'),
        'range':('Focus by received signal strength.','A stronger RSSI filter favors stronger signals. It does not establish a physical radius.'),
        'alerts':('Review signals in context.','Device names and signature matches are estimates. Unverified signatures are labeled.'),
        'baseline':('Know what is already present.','Baseline devices remain visible and do not trigger new-device alerts.'),
        'finder':('Move a few steps. Pause. Compare.','Watch fresh signal trends. Walls, interference, and orientation can affect readings.'),
        'report':('Keep the observations you need.','Export a CSV report to a location you choose. Sessions remain on your device.'),
    }
    heading,body=notes.get(scene['id'],('Wireless awareness, on your device.','Demonstration uses simulated devices and signal readings.'))
    d.rounded_rectangle((100,716,1190,925),radius=24,fill='#11232F',outline=LINE,width=1)
    d.text((128,747),heading,font=font(27,True),fill=WHITE)
    d.multiline_text((128,798),wrap(d,body,font(25),1010),font=font(25),fill=MUTED,spacing=10)
    for i in range(total):
        x=102+i*180
        d.rounded_rectangle((x,974,x+154,979),radius=3,fill=MINT if i<index else '#243540')
    d.text((102,1014),'SIMULATED DEMO  |  Signal strength is not a direction or distance measurement.',font=font(17),fill=MUTED)
    d.rounded_rectangle((1336,27,1800,1053),radius=36,fill='#03080D',outline=LINE,width=3)
    im.save(path)

def run(cmd:list[str]):
    completed=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    if completed.returncode: raise RuntimeError(completed.stderr[-5000:])

def duration(path:Path)->float:
    return float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',str(path)],text=True))

def stamp(seconds:float)->str:
    milliseconds=round(seconds*1000)
    return f'{milliseconds//3600000:02}:{milliseconds//60000%60:02}:{milliseconds//1000%60:02},{milliseconds%1000:03}'

def main():
    for folder in (SHOTS,VIDEO): folder.mkdir(parents=True,exist_ok=True)
    scenes=json.loads((WORK/'edit-plan.json').read_text())
    raw=CAPTURE/'walkthrough-raw.mp4'
    if len(scenes)!=6: raise RuntimeError('Incomplete edit plan')
    if '--finalize' in sys.argv:
        finalize(scenes)
        return
    for source in sorted(CAPTURE.glob('*.png')): shutil.copyfile(source,SHOTS/source.name)
    shutil.copyfile(CAPTURE/'sample-session-demo.csv',MEDIA/'sample-session-demo.csv')
    segments=[]; manifest=[]
    for number,scene in enumerate(scenes,1):
        start=scene['source_start']; length=scene['source_duration']
        length_out=scene['duration']
        background=WORK/f"{scene['id']}-background.png"
        panel(scene,number,len(scenes),background)
        segment=WORK/f"{number:02d}-{scene['id']}.mp4"
        fade_out=max(0,length_out-.25)
        graph=(f'[0:v]fps=30,scale=448:996:flags=lanczos,setsar=1,'
               f'tpad=stop_mode=clone:stop_duration={length_out+1:.3f}[phone];'
               f'[1:v][phone]overlay=1344:42:shortest=1,format=yuv420p,'
               f'fade=t=in:st=0:d=0.25,fade=t=out:st={fade_out:.3f}:d=0.25[v]')
        run(['ffmpeg','-y','-hide_banner','-loglevel','error','-ss',f'{start:.3f}','-t',f'{length:.3f}','-i',str(raw),
             '-loop','1','-framerate','30','-i',str(background),'-filter_complex',graph,
             '-map','[v]','-an','-t',f'{length_out:.3f}','-c:v','libx264','-preset','fast','-crf','19',
             '-movflags','+faststart',str(segment)])
        segments.append(segment)
        manifest.append(dict(chapter=scene['id'],start_seconds=scene['timeline_start'],duration_seconds=length_out))
        print(f"Rendered {scene['id']}: {length_out:.1f}s",flush=True)
    listing=WORK/'segments.txt'
    listing.write_text('\n'.join(f"file '{p}'" for p in segments)+'\n')
    final=WORK/'demo-picture.mp4'
    run(['ffmpeg','-y','-hide_banner','-loglevel','error','-f','concat','-safe','0','-i',str(listing),'-c','copy','-movflags','+faststart',str(final)])
    (VIDEO/'chapters.json').write_text(json.dumps(manifest,indent=2)+'\n')
    run(['ffmpeg','-y','-hide_banner','-loglevel','error','-ss','3','-i',str(final),'-frames:v','1',str(VIDEO/'demo-poster-en.jpg')])
    # A shareable contact sheet; screenshot pixels themselves remain unaltered in screenshots/.
    shots=sorted(SHOTS.glob('*.png'))
    sheet=Image.new('RGB',(1920,1440),BG); d=ImageDraw.Draw(sheet)
    d.text((70,45),'IoT Scanner',font=font(44,True),fill=WHITE)
    d.text((70,105),'English screenshots  |  Actual Android app  |  Simulated demo signals',font=font(22),fill=MUTED)
    labels=['Nearby devices','Scan range','Nearby range applied','Review new signals','Record a baseline','Signal getting stronger','Signal getting weaker','Session report']
    for i,shot in enumerate(shots):
        x=70+(i%4)*470; y=180+(i//4)*610
        d.text((x,y),f'{i+1:02d}  {labels[i]}',font=font(20,True),fill=MINT)
        image=Image.open(shot).convert('RGB'); image.thumbnail((238,530),Image.Resampling.LANCZOS)
        sheet.paste(image,(x+70,y+40))
    sheet.save(MEDIA/'screenshots-overview-en.jpg',quality=94)
    package=MEDIA/'iot-scanner-screenshots-en.zip'
    with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as archive:
        for shot in shots: archive.write(shot,f'screenshots/{shot.name}')
        archive.write(MEDIA/'screenshots-overview-en.jpg','screenshots-overview-en.jpg')
    print(json.dumps({'picture':str(final),'duration_seconds':duration(final),'screenshots':len(shots),'zip':str(package)}),flush=True)
    if '--picture-only' not in sys.argv: finalize(scenes)


def finalize(scenes):
    picture=WORK/'demo-picture.mp4'
    narration=VIDEO/'iot-scanner-narration-subtitlekit.wav'
    subtitles=VIDEO/'iot-scanner-demo-en.srt'
    final=VIDEO/'iot-scanner-demo-en.mp4'
    if not narration.exists(): raise FileNotFoundError('Download the user-requested SubtitleKit WAV first')
    seconds=sum(scene['duration'] for scene in scenes)
    run(['ffmpeg','-y','-hide_banner','-loglevel','error','-i',str(picture),'-i',str(narration),'-i',str(subtitles),
        '-map','0:v:0','-map','1:a:0','-map','2:0','-c:v','copy','-af',f'apad,atrim=duration={seconds:.3f},loudnorm=I=-16:LRA=11:TP=-1.5',
        '-c:a','aac','-b:a','192k','-ar','48000','-ac','2','-c:s','mov_text',
        '-metadata','title=IoT Scanner - English Demo',
        '-metadata','comment=Actual Android app; simulated demo signals; narration generated with SubtitleKit',
        '-metadata:s:a:0','language=eng','-metadata:s:s:0','language=eng',
        '-movflags','+faststart',str(final)])
    print(json.dumps({'video':str(final),'duration_seconds':duration(final),'narration':'SubtitleKit'}),flush=True)

if __name__=='__main__': main()
