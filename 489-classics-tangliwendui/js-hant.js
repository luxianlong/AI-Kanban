(function(){
  var b=document.body, d=document;

  var ai=d.getElementById('ai');
  function setAi(on){
    b.classList.toggle('ai-on', on);
    ai.setAttribute('aria-pressed', on?'true':'false');
    try{ localStorage.setItem('sl-ai', on?'on':'off'); }catch(e){}
  }
  var savedAi='off';
  try{ savedAi=localStorage.getItem('sl-ai')||'off'; }catch(e){}
  setAi(savedAi==='on');
  ai.onclick=function(){ setAi(ai.getAttribute('aria-pressed')!=='true'); };

  // highlight the section currently in view
  var links=[].slice.call(d.querySelectorAll('nav.toc a'));
  var map={}; links.forEach(function(a){ map[a.getAttribute('href').slice(1)]=a; });
  var heads=[].slice.call(d.querySelectorAll('main h2[id]'));
  var active=null;
  function sync(){
    var cur=heads[0];
    for(var i=0;i<heads.length;i++){
      if(heads[i].getBoundingClientRect().top<=90) cur=heads[i]; else break;
    }
    var id=cur&&cur.id;
    if(id===active) return;
    if(active&&map[active]) map[active].classList.remove('on');
    active=id;
    if(map[id]){ map[id].classList.add('on');
      if(map[id].scrollIntoView) map[id].scrollIntoView({block:'nearest'}); }
  }
  if(heads.length){
    var tick=false;
    addEventListener('scroll',function(){
      if(tick) return; tick=true;
      requestAnimationFrame(function(){ tick=false; sync(); });
    },{passive:true});
    sync();
  }
})();