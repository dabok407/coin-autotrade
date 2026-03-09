(function(){
  'use strict';

  function pad(n){ return (n<10?('0'+n):(''+n)); }
  function fmtDate(d){ return d.getFullYear() + '-' + pad(d.getMonth()+1) + '-' + pad(d.getDate()); }
  function parseYmd(s){
    if(!s) return null;
    var m = /^\s*(\d{4})-(\d{1,2})-(\d{1,2})\s*$/.exec(s);
    if(!m) return null;
    var y = parseInt(m[1],10), mo=parseInt(m[2],10)-1, da=parseInt(m[3],10);
    var d = new Date(y,mo,da);
    // guard invalid (e.g. 2026-02-31)
    if(d.getFullYear()!==y || d.getMonth()!==mo || d.getDate()!==da) return null;
    return d;
  }

  var pop = null;
  var activeInput = null;
  var view = null; // first day of view month
  var selected = null;

  function close(){
    if(pop){ pop.remove(); pop=null; }
    activeInput=null;
  }

  function build(){
    pop = document.createElement('div');
    pop.className = 'dp-pop';

    pop.innerHTML = ''+
      '<div class="dp-head">'+
        '<div class="dp-title" id="dpTitle"></div>'+
        '<div class="dp-nav">'+
          '<button type="button" class="dp-btn" data-dp="prev" aria-label="Previous month">‹</button>'+
          '<button type="button" class="dp-btn" data-dp="next" aria-label="Next month">›</button>'+
        '</div>'+
      '</div>'+
      '<div class="dp-grid">'+
        '<div class="dp-week">'+
          '<div>일</div><div>월</div><div>화</div><div>수</div><div>목</div><div>금</div><div>토</div>'+
        '</div>'+
        '<div class="dp-days" id="dpDays"></div>'+
      '</div>'+
      '<div class="dp-foot">'+
        '<button type="button" class="dp-link" data-dp="today">오늘</button>'+
        '<button type="button" class="dp-link" data-dp="clear">지우기</button>'+
        '<button type="button" class="dp-link" data-dp="close">닫기</button>'+
      '</div>';

    pop.addEventListener('click', function(e){
      var t = e.target;
      if(!t) return;
      var act = t.getAttribute('data-dp');
      if(act==='prev'){ view = new Date(view.getFullYear(), view.getMonth()-1, 1); render(); return; }
      if(act==='next'){ view = new Date(view.getFullYear(), view.getMonth()+1, 1); render(); return; }
      if(act==='today'){
        var d = new Date();
        selected = new Date(d.getFullYear(), d.getMonth(), d.getDate());
        applySelected();
        return;
      }
      if(act==='clear'){
        if(activeInput) activeInput.value='';
        close();
        return;
      }
      if(act==='close'){
        close();
        return;
      }

      var day = t.getAttribute('data-day');
      if(day){
        var parts = day.split('-');
        selected = new Date(parseInt(parts[0],10), parseInt(parts[1],10)-1, parseInt(parts[2],10));
        applySelected();
      }
    });

    document.body.appendChild(pop);
  }

  function position(){
    if(!pop || !activeInput) return;
    var r = activeInput.getBoundingClientRect();
    var top = r.bottom + 8;
    var left = r.left;
    // keep in viewport
    var maxLeft = window.innerWidth - pop.offsetWidth - 10;
    if(left > maxLeft) left = Math.max(10, maxLeft);
    var maxTop = window.innerHeight - pop.offsetHeight - 10;
    if(top > maxTop) top = Math.max(10, r.top - pop.offsetHeight - 8);
    pop.style.top = top + 'px';
    pop.style.left = left + 'px';
  }

  function render(){
    if(!pop) return;
    var title = pop.querySelector('#dpTitle');
    var days = pop.querySelector('#dpDays');
    var y = view.getFullYear();
    var m = view.getMonth();
    title.textContent = y + '년 ' + (m+1) + '월';

    // start from Sunday of the first week
    var first = new Date(y, m, 1);
    var start = new Date(first);
    start.setDate(first.getDate() - first.getDay());

    var today = new Date();
    var tY=today.getFullYear(), tM=today.getMonth(), tD=today.getDate();

    var html = '';
    for(var i=0;i<42;i++){
      var d = new Date(start.getFullYear(), start.getMonth(), start.getDate()+i);
      var cls = 'dp-day';
      if(d.getMonth()!==m) cls += ' is-out';
      if(d.getFullYear()===tY && d.getMonth()===tM && d.getDate()===tD) cls += ' is-today';
      if(selected && d.getFullYear()===selected.getFullYear() && d.getMonth()===selected.getMonth() && d.getDate()===selected.getDate()) cls += ' is-selected';
      var key = fmtDate(d);
      html += '<div class="'+cls+'" data-day="'+key+'">'+d.getDate()+'</div>';
    }
    days.innerHTML = html;
    position();
  }

  function applySelected(){
    if(activeInput && selected){
      activeInput.value = fmtDate(selected);
      // emit change event so existing logic (Period -> range, etc.) can react if needed
      var ev = new Event('change', {bubbles:true});
      activeInput.dispatchEvent(ev);
    }
    close();
  }

  function openFor(input){
    activeInput = input;
    selected = parseYmd(input.value);
    var base = selected || new Date();
    view = new Date(base.getFullYear(), base.getMonth(), 1);
    if(!pop) build();
    render();
  }

  function onDocClick(e){
    if(!pop) return;
    var t = e.target;
    if(!t) return;
    if(pop.contains(t)) return;
    if(activeInput && (t===activeInput)) return;
    close();
  }

  function bindInputs(){
    var inputs = document.querySelectorAll('input.dp-date');
    for(var i=0;i<inputs.length;i++){
      (function(inp){
        inp.addEventListener('focus', function(){ openFor(inp); });
        inp.addEventListener('click', function(){ openFor(inp); });
        inp.addEventListener('keydown', function(e){
          if(e.key==='Escape'){ close(); }
          if(e.key==='Enter'){ /* allow manual */
            var d = parseYmd(inp.value);
            if(d){ selected=d; }
          }
        });
        // keep value normalized if user types
        inp.addEventListener('blur', function(){
          if(!inp.value) return;
          var d = parseYmd(inp.value);
          if(d) inp.value = fmtDate(d);
        });
      })(inputs[i]);
    }
  }

  window.addEventListener('resize', position);
  document.addEventListener('click', onDocClick);
  document.addEventListener('DOMContentLoaded', bindInputs);
})();
