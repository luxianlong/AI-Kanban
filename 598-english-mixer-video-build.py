import html, json
E = html.escape
A = "/img/english-learning-mixer-video"  # asset dir (served publicly)

speakers = [
 ("Jeyong", "Korea", "I'm a student abroad in Japan and I go back to Korea during summer break and during my free time, I usually spend a lot of time with my older sister and I feel comfortable talking to her because she cares for me very much and gives me advice of how a university life should be like, and we go shopping together, hang out, eat food and go to movies together and so on."),
 ("Joel", "United States", "Well, I used to hang out with my friends all the time, go out on the weekends, and maybe we go to a bar drinking or something, but I'm married and I have a son now so I usually hang out with my family. I don't really go out much at all anymore. I'm happier just staying home and playing with my kid."),
 ("Lucinda", "New Zealand", "In my free time I usually hang out with my boyfriend. We at the moment, is the NBA League Pass on Sky TV and we've just been watching the last two games and that's about it. That's all I really do is watch TV with my free time."),
 ("Martin", "United States", "Well, when I have free time, the person I usually always hang out with would have to be my girlfriend. We always, we share a common interest and like to spend time with each other, just go out, walk around, see movies, or just chat and have coffee or dinner together, but normally I'm very busy these days and I don't have that much free time."),
 ("Ruth", "England", "Who do I hang out with? Well, I think that depends on the situation and on the day. When I'm at work, at lunchtime I hang out with my workmates, but in the evenings, and at the weekends, I hang around with my best friend, and with my other friends, and often I also hang around with my parents. I really like spending time with them. I don't usually hang around with anybody from school because I'm not really in touch with them anymore, but I do like to spend time with my parents."),
 ("Tom", "England", "I came here a month ago and I don't speak the language and I don't really know anybody so in my free time I stay home. There's the radio on the internet. That keeps me company and I've learned how to cook up some simple food to stop myself from getting too hungry, but in terms of who do I hang out with, I might say hello to the lady in the supermarket, but no one really."),
]
video2 = [
 ("Tu","Hello, I'm Tu and I'm from Vietnam."),
 ("JM","And I'm JM from the Philippines."),
 ("Tu","Our question today is where do you like to hang out? For me a favorite place to hang out is going shopping and eating out in some restaurant with my friends. I can spend hours in some supermarket to shop and to go around to see what kind of like new fashion and things like some type of stuff like small stuff and then go eating with my friends but I don't really like to hang out with some guys because sometimes they feel so bored with the way I hang out too long in some shops and some restaurants so I prefer going out with some of my girlfriends."),
 ("JM","I see. Well for me I like to hang out at the beach, just laying on the sand catching some rays, thinking of the Indian Ocean around then, hanging out with friends and just relaxing the whole day. And then after that I'd like to go home and play some video games and I like video games a lot so I can play that the whole night and then just repeat the whole process again the next morning. Yeah, that's how I like to hang out."),
 ("Tu","Cool. Do you like video games?"),
 ("JM","Yes. I love video games, I'm always playing them."),
 ("Tu","I don't like it. I don't like it."),
 ("JM","I'm sorry to hear that."),
 ("Tu","OK. See you bye bye."),
 ("JM","Right, see you bye bye."),
]
vocab = [
 ("spend time with","v1-spendtime.mp3","I usually spend time with my sister.",
  "To <b>spend time with</b> someone means to do things together. You can spend time with anybody — your friend, your pet, or your grandmother. Any time you choose to be with someone, you spend time with them.",
  ["I wish I could spend more time with my family.","In smaller classes, teachers can spend more time with individual students."]),
 ("hang out","v2-hangout.mp3","We hang out and eat food.",
  "<b>Hanging out</b> means doing something relaxing, like watching TV or shopping with friends.",
  ["The girls like to hang out at the mall on weekends.","Let's hang out on Sunday."]),
 ("common interest","v3-common.mp3","We share a common interest.",
  "A <b>common interest</b> is something that two people like and can enjoy together. If you and your friend both like tennis, you can play tennis together and talk about tennis together. You have a common interest.",
  ["My dad and I have a common interest in fishing.","Facebook is a great way to meet people with common interests."]),
 ("workmates","v4-workmates.mp3","At lunch, I hang out with my workmates.",
  "<b>Workmates</b> are people we work with. Workmates are also called colleagues or co-workers. <i>Workmate</i> is a less formal word and can apply to any place you work.",
  ["My workmates are from many different countries.","John often goes to football matches with his workmates."]),
 ("in touch","v5-intouch.mp3","I'm not really in touch with them anymore.",
  "To be <b>in touch</b> with someone means to stay in contact.",
  ["Nowadays, most people use e-mail to stay in touch with friends.","When my parents were young, they wrote letters to stay in touch."]),
]
quiz = [
 ("What does Jeyong do with his sister?",["They go to the cinema","They go to school"],[0]),
 ("Why does Joel like to stay home?",["To be with friends","To be with his son"],[1]),
 ("What is Lucinda's hobby?",["She watches television","She doesn't have free time"],[0]),
 ("What does Martin do with his girlfriend?",["They go for walks","They cook together"],[0]),
 ("Ruth likes to hang out with ________.",["her classmates","her family"],[1]),
 ("Who are Tom's friends?",["He doesn't have any","Radio employees"],[0]),
 ("Who do these people hang out with? (choose two)",["co-workers","social groups","school friends","family"],[0,3]),
]
gaps = [
 ("I will get","with you later today to confirm our plans for tomorrow.","in touch"),
 ("Now we are all so busy with our lives that we don't","together like we used to.","spend time"),
 ("All of my","are a lot older than me.","workmates"),
 ("We were really tired this weekend so we just","at home.","hung out"),
 ("It's difficult for me to be friends with someone if I feel like we don't have common",".","interests"),
]

def video_block(vid, title, dur, thumb, desc):
    return f'''<figure class="video">
  <div class="frame" data-vid="{vid}">
    <button class="poster" type="button" aria-label="Play {E(title)}" style="background-image:url('{A}/{thumb}')">
      <span class="play">▶</span><span class="dur">{dur}</span>
    </button>
  </div>
  <figcaption><b>{E(title)}</b> — {E(desc)} ·
    <a href="https://vimeo.com/{vid}" target="_blank" rel="noopener">open on Vimeo ↗</a></figcaption>
</figure>'''

sp_html = "\n".join(
  f'<div class="turn"><div class="who">{E(n)}<span>{E(c)}</span></div><p>{E(t)}</p></div>' for n,c,t in speakers)
v2_html = "\n".join(f'<div class="turn"><div class="who">{E(n)}</div><p>{E(t)}</p></div>' for n,t in video2)
voc_html = "\n".join(f'''<div class="vcard">
  <h3>{E(w)}</h3>
  <audio controls preload="none" src="{A}/{f}"></audio>
  <p class="ex">“{E(ex)}”</p>
  <p>{d}</p>
  <ul>{''.join(f"<li>{E(x)}</li>" for x in xs)}</ul>
</div>''' for w,f,ex,d,xs in vocab)
q_html = "\n".join(
  f'<fieldset class="q" data-ans="{",".join(map(str,a))}"><legend>{i+1}. {E(q)}</legend>' +
  "".join(f'<label><input type="{"checkbox" if len(a)>1 else "radio"}" name="q{i}" value="{j}"> {chr(97+j)}) {E(o)}</label>' for j,o in enumerate(opts)) +
  '</fieldset>' for i,(q,opts,a) in enumerate(quiz))
g_html = "\n".join(f'<li>{E(a)} <input class="gap" data-ans="{E(k)}" size="11" aria-label="blank {i+1}"> {E(b)}</li>' for i,(a,b,k) in enumerate(gaps))

page = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>English Learning Mixer-Video</title>
<meta name="description" content="ELLLO Mixer #14 “Hanging Out” — video, audio, transcript, quiz and vocabulary (B1 Intermediate).">
<style>
:root{{--bg:#faf8f4;--fg:#1f2328;--muted:#6b6f76;--line:#e4e0d8;--card:#fff;--acc:#1d6f5f;--acc2:#e9f3f0;--bad:#b3261e;--good:#1d6f5f}}
@media (prefers-color-scheme:dark){{:root{{--bg:#16181b;--fg:#e6e6e6;--muted:#9aa0a6;--line:#2c3036;--card:#1e2126;--acc:#5cc2a8;--acc2:#1f2f2b;--bad:#ff8a80;--good:#5cc2a8}}}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--fg);font:16px/1.65 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}}
main{{max-width:860px;margin:0 auto;padding:28px 18px 80px}}
header .kicker{{font-size:12.5px;letter-spacing:.12em;text-transform:uppercase;color:var(--acc);font-weight:600}}
h1{{font-size:32px;line-height:1.2;margin:.2em 0 .3em}}
h2{{font-size:22px;margin:42px 0 12px;padding-top:10px;border-top:1px solid var(--line)}}
.lead{{color:var(--muted);margin:0 0 6px}}
nav.toc{{display:flex;flex-wrap:wrap;gap:8px;margin:18px 0 6px}}
nav.toc a{{font-size:14px;text-decoration:none;color:var(--acc);background:var(--acc2);padding:4px 11px;border-radius:99px}}
.video{{margin:14px 0 22px}}
.frame{{position:relative;aspect-ratio:16/9;background:#000;border-radius:12px;overflow:hidden}}
.frame iframe{{position:absolute;inset:0;width:100%;height:100%;border:0}}
.poster{{position:absolute;inset:0;width:100%;height:100%;border:0;cursor:pointer;background:#000 center/cover no-repeat}}
.poster .play{{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:74px;height:74px;border-radius:50%;background:rgba(0,0,0,.62);color:#fff;font-size:28px;line-height:74px;padding-left:6px}}
.poster:hover .play{{background:var(--acc)}}
.poster .dur{{position:absolute;right:10px;bottom:10px;background:rgba(0,0,0,.7);color:#fff;font-size:13px;padding:1px 8px;border-radius:5px}}
figcaption{{font-size:14px;color:var(--muted);margin-top:8px}}
figcaption a,a{{color:var(--acc)}}
audio{{width:100%;margin:6px 0}}
.turn{{display:grid;grid-template-columns:120px 1fr;gap:14px;padding:12px 0;border-bottom:1px dashed var(--line)}}
.turn p{{margin:0}}
.who{{font-weight:600}}.who span{{display:block;font-weight:400;font-size:13px;color:var(--muted)}}
@media (max-width:560px){{.turn{{grid-template-columns:1fr;gap:2px}}}}
details{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:4px 16px;margin:10px 0}}
summary{{cursor:pointer;font-weight:600;padding:8px 0;color:var(--acc)}}
.vgrid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:14px}}
.vcard{{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px}}
.vcard h3{{margin:0 0 4px;font-size:19px;color:var(--acc)}}
.vcard .ex{{font-style:italic;margin:4px 0}}
.vcard p,.vcard ul{{font-size:14.5px;margin:6px 0}}.vcard ul{{padding-left:18px;color:var(--muted)}}
.q{{border:1px solid var(--line);border-radius:10px;background:var(--card);margin:10px 0;padding:8px 16px 12px}}
.q legend{{font-weight:600;padding:0 6px}}
.q label{{display:block;padding:3px 0;cursor:pointer}}
.q.ok{{border-color:var(--good)}}.q.no{{border-color:var(--bad)}}
.bank{{background:var(--acc2);border-radius:8px;padding:8px 14px;font-weight:600;color:var(--acc)}}
ol.gaps li{{margin:10px 0}}
input.gap{{font:inherit;padding:2px 6px;border:0;border-bottom:2px solid var(--line);background:transparent;color:var(--fg);text-align:center}}
input.gap.ok{{border-color:var(--good)}}input.gap.no{{border-color:var(--bad)}}
button.btn{{font:inherit;font-weight:600;border:0;border-radius:8px;padding:7px 16px;background:var(--acc);color:#fff;cursor:pointer;margin-right:8px}}
button.btn.ghost{{background:transparent;color:var(--acc);border:1px solid var(--acc)}}
.score{{font-weight:600;margin-left:6px}}
.tips li{{margin:6px 0}}
footer{{margin-top:50px;font-size:13.5px;color:var(--muted);border-top:1px solid var(--line);padding-top:14px}}
</style></head>
<body><main>
<header>
  <div class="kicker">English Learning · ELLLO Mixer #14 · Intermediate B1</div>
  <h1>English Learning Mixer-Video: “Hanging Out”</h1>
  <p class="lead">Six people talk about who they hang out with in their free time, plus a short video conversation about <i>where</i> two students like to hang out.</p>
  <nav class="toc"><a href="#video">Video</a><a href="#audio">Full audio</a><a href="#script">Script</a><a href="#quiz">Quiz</a><a href="#vocab">Vocabulary</a><a href="#vquiz">Vocab quiz</a><a href="#video2">Bonus video</a><a href="#practice">Practice</a></nav>
</header>

<h2 id="video">1 · Watch: Mixer #14 “Hanging Out”</h2>
{video_block("544153615","Mixer 14 Hanging Out","3:13","thumb-544153615.jpg","People talk about who they hang out with.")}
<p class="lead">Suggested routine: watch once with no text → take the <a href="#quiz">quiz</a> → watch again while reading the <a href="#script">script</a> → study the <a href="#vocab">vocabulary</a>.</p>

<h2 id="audio">2 · Listen: full audio</h2>
<p class="lead">The same six answers as audio only (saved here, so it plays even when the video won't load).</p>
<audio controls preload="metadata" src="{A}/mixer014-full.mp3"></audio>
<p class="lead" style="font-size:14px"><a href="{A}/mixer014-full.mp3" download>Download MP3</a> (740 KB)</p>

<h2 id="script">3 · Script</h2>
<details><summary>Show / hide the transcript (try the quiz first!)</summary>
{sp_html}
</details>

<h2 id="quiz">4 · Listening quiz</h2>
<p class="lead">Answer these questions about the interview.</p>
<form id="quizform" onsubmit="return false">
{q_html}
<button class="btn" type="button" id="qcheck">Check answers</button><button class="btn ghost" type="button" id="qreset">Reset</button><span class="score" id="qscore"></span>
</form>

<h2 id="vocab">5 · Vocabulary</h2>
<p class="lead">Five expressions from the lesson. Each one has a short audio clip.</p>
<div class="vgrid">
{voc_html}
</div>

<h2 id="vquiz">6 · Vocabulary quiz</h2>
<p class="bank">spend time • hung out • interests • workmates • in touch</p>
<ol class="gaps">
{g_html}
</ol>
<button class="btn" type="button" id="gcheck">Check answers</button><button class="btn ghost" type="button" id="greset">Reset</button><span class="score" id="gscore"></span>

<h2 id="video2">7 · Bonus: Mixer Video #14 “Where do you like to hang out?”</h2>
{video_block("132524563","Mixer video #14 — Where do you like to hang out?","1:43","thumb-132524563.jpg","Tu (Vietnam) and JM (Philippines) talk about where they like to hang out.")}
<details><summary>Show / hide the transcript</summary>
{v2_html}
</details>

<h2 id="practice">8 · Speaking practice</h2>
<ul class="tips">
  <li>Who do <b>you</b> usually hang out with in your free time? Answer in 30 seconds, like the speakers.</li>
  <li>Do you share a <b>common interest</b> with a friend or family member? What do you do together?</li>
  <li>Who from your school days are you still <b>in touch</b> with? How do you stay in touch?</li>
  <li>Do you hang out with your <b>workmates</b> or classmates after work or class? Why or why not?</li>
  <li>Where do you like to hang out: the shops, the beach, or at home with video games, like in the bonus video?</li>
</ul>

<footer>
  Source: <a href="https://elllo.org/english/Mixer001/T014-HangOut.htm" target="_blank" rel="noopener">ELLLO Mixer #14 “Hanging Out”</a> and
  <a href="https://elllo.org/video/M001/M014Hangout.htm" target="_blank" rel="noopener">Mixer Video #14</a> by elllo.org (Todd Beuckens).
  Videos stream from elllo's Vimeo account; the audio, transcript, quiz and vocabulary are elllo's lesson materials, gathered here on one page for personal study.
</footer>
</main>
<script>
document.querySelectorAll('.frame').forEach(function(f){{
  f.querySelector('.poster').addEventListener('click',function(){{
    var i=document.createElement('iframe');
    i.src='https://player.vimeo.com/video/'+f.dataset.vid+'?autoplay=1&dnt=1';
    i.allow='autoplay; fullscreen; picture-in-picture'; i.allowFullscreen=true;
    i.title='Vimeo video'; f.innerHTML=''; f.appendChild(i);
  }});
}});
function qCheck(){{
  var ok=0,qs=document.querySelectorAll('.q');
  qs.forEach(function(q){{
    var want=q.dataset.ans.split(',').sort().join(','),
        got=[].map.call(q.querySelectorAll('input:checked'),function(x){{return x.value}}).sort().join(',');
    var r=got===want; q.classList.toggle('ok',r); q.classList.toggle('no',!r); if(r)ok++;
  }});
  document.getElementById('qscore').textContent=ok+' / '+qs.length+(ok===qs.length?' — Excellent, top marks!':'');
}}
document.getElementById('qcheck').onclick=qCheck;
document.getElementById('qreset').onclick=function(){{
  document.getElementById('quizform').reset();
  document.querySelectorAll('.q').forEach(function(q){{q.classList.remove('ok','no')}});
  document.getElementById('qscore').textContent='';
}};
document.getElementById('gcheck').onclick=function(){{
  var ok=0,gs=document.querySelectorAll('input.gap');
  gs.forEach(function(g){{
    var r=g.value.trim().toLowerCase().replace(/\\s+/g,' ')===g.dataset.ans;
    g.classList.toggle('ok',r); g.classList.toggle('no',!r); if(r)ok++;
    if(!r) g.placeholder=g.dataset.ans;
  }});
  document.getElementById('gscore').textContent=ok+' / '+gs.length;
}};
document.getElementById('greset').onclick=function(){{
  document.querySelectorAll('input.gap').forEach(function(g){{g.value='';g.placeholder='';g.classList.remove('ok','no')}});
  document.getElementById('gscore').textContent='';
}};
</script>
</body></html>
'''
open("/tmp/kanban-worker/todo-598/build/english-learning-mixer-video.html","w",encoding="utf-8").write(page)
print(len(page))
