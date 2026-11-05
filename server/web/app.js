const $ = id => document.getElementById(id);
let current, editing = null, editingRevision = 0, busy = false;
let studySignature = '';
const categories = {AI:'AI 开发',ENGLISH:'英语沟通',MEAL:'饮食备餐',WORKOUT:'运动训练',LIFE:'工作生活'};
const statuses = {PENDING:'待开始',RUNNING:'进行中',PAUSED:'已暂停',COMPLETED:'完整完成',PARTIAL:'部分完成',SKIPPED:'已跳过'};
function message(text){$('message').textContent=text;$('message').style.display='block';setTimeout(()=>$('message').style.display='none',5000)}
async function request(path, body){const r=await fetch(path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});const data=await r.json();if(!r.ok)throw Error(data.error||'请求未完成');return data}
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n}
function item(title, detail){const n=el('div',undefined,'item');n.append(el('strong',title),el('p',detail));return n}
function clearEditor(){editing=null;editingRevision=0;$('editor').reset();$('editor-title').textContent='新增电脑计划';const d=new Date();d.setMinutes(d.getMinutes()-d.getTimezoneOffset());$('scheduled').value=d.toISOString().slice(0,16)}
function edit(task,revision){editing=structuredClone(task);editingRevision=revision;$('title').value=task.title;$('category').value=task.category;$('scheduled').value=task.scheduledAt.slice(0,16);$('duration').value=task.durationMinutes;$('steps').value=task.steps||'';$('criteria').value=task.criteria||'';$('resource-url').value=task.resourceUrl||'';$('task-note').value=task.note||'';$('editor-title').textContent='修改未来事项';$('editor').scrollIntoView({behavior:'smooth',block:'start'})}
function safeResource(value){try{const u=new URL(value);return ['http:','https:'].includes(u.protocol)&&!u.username&&!u.password?u.href:null}catch{return null}}
function renderStudy(){
 if(!current)return;
 const courses=current.entities.filter(e=>!e.deleted&&e.key.startsWith('task:')&&!e.payload.isDemo&&['AI','ENGLISH'].includes(e.payload.category)).sort((a,b)=>a.payload.scheduledAt.localeCompare(b.payload.scheduledAt));
 const days=[...new Set(courses.map(e=>e.payload.scheduledAt.slice(0,10)))];
 const now=new Date();now.setMinutes(now.getMinutes()-now.getTimezoneOffset());
 const selected=$('study-date').value||courses.find(e=>e.payload.scheduledAt>=now.toISOString().slice(0,16)&&e.payload.resourceUrl)?.payload.scheduledAt.slice(0,10)||days[0]||now.toISOString().slice(0,10);
 if(JSON.stringify(days)!==JSON.stringify([...$('study-date').options].map(x=>x.value))){$('study-date').replaceChildren(...days.map(d=>{const option=el('option',d);option.value=d;return option}));$('study-date').value=days.includes(selected)?selected:days[0]||''}
 $('study-sync').textContent=`电脑计划 v${current.planVersion} · 手机${current.devices.some(d=>d.online)?'在线':'暂时离线'} · 已读取电脑保存的内容`;
 const filtered=courses.filter(e=>e.payload.scheduledAt.startsWith($('study-date').value));
 const signature=JSON.stringify(filtered);
 if(signature===studySignature)return;studySignature=signature;$('study-tasks').replaceChildren();
 for(const e of filtered){
  const t=e.payload,card=el('article',undefined,'study-card');
  card.append(el('div',`${t.scheduledAt.slice(11)} · ${categories[t.category]} · ${t.durationMinutes}分钟日历预算`,'course-meta'),el('h3',t.title),el('p',`${statuses[t.status]} · 已回传计时 ${Math.floor((t.accumulatedSeconds||0)/60)} 分钟`,'course-meta'));
  if(t.criteria)card.append(el('p',t.criteria));
  const links=[...new Set([t.resourceUrl,...((t.note||'').match(/https?:\/\/[^\s<>"'，。；]+/g)||[])].filter(Boolean).map(safeResource).filter(Boolean))];
  links.forEach((url,i)=>{const a=el('a',i===0?'在电脑打开主资料 ↗':`补充资料 ${i} ↗`,'resource-link');a.href=url;a.target='_blank';a.rel='noopener noreferrer';a.title=url;card.append(a)});
  if(!links.length)card.append(el('p','课程或资料待学习导师交接；这里只保留时间窗口。','hint'));
  const detail=el('details');detail.append(el('summary','学习步骤、记录要求与资源地址'),el('pre',[t.steps,t.note].filter(Boolean).join('\n\n')));card.append(detail);
  if(!t.executionStartedAt&&!['RUNNING','PAUSED','COMPLETED','PARTIAL','SKIPPED'].includes(t.status)){const b=el('button','编辑课程与资料','secondary');b.onclick=()=>edit(t,e.revision);card.append(b)}
  $('study-tasks').append(card);
 }
 if(!filtered.length)$('study-tasks').append(el('p','这个日期还没有学习事项。可以新增课程，或等待导师交接。','hint'));
}
async function refresh(){if(busy)return;try{current=await request('/api/desktop');$('health').textContent='电脑服务已就绪';$('address').value=current.lanAddresses.length?'http://'+current.lanAddresses[0]+':8081':'请查看电脑局域网 IPv4 地址';$('code').textContent=current.pairCode;$('expiry').textContent='有效至 '+new Date(current.pairExpiresAt).toLocaleTimeString();$('version').textContent='电脑计划 v'+current.planVersion;
 $('recipe-address').value=current.lanAddresses.length?'http://'+current.lanAddresses[0]+':'+location.port+'/recipes/':'暂无可用局域网地址';$('recipe-lan').href=current.lanAddresses.length?$('recipe-address').value:'/recipes/';
 renderStudy();$('browser-requests').replaceChildren();for(const r of current.browserRequests||[]){$('browser-requests').append(item(r.title,`${new Date(r.createdAt).toLocaleString()} · ${{requested:'已请求浏览器打开，页面加载未确认',failed:'浏览器启动失败',pending:'打开状态未确认，未自动重发'}[r.state]||r.state}`))}if(!(current.browserRequests||[]).length)$('browser-requests').append(el('p','还没有手机打开请求。'));
 $('requests').replaceChildren();for(const r of current.connectionRequests||[]){const row=item(r.name+' 请求连接','请求 '+r.id.slice(-4).toUpperCase()+' · '+(r.status==='approved'?'已允许，等待手机接收':'请确认这是你的手机'));if(r.status==='pending'){for(const [allow,label] of [[true,'允许连接'],[false,'拒绝']]){const b=el('button',label,allow?'':'secondary');b.onclick=async()=>{try{await request('/api/connection-decide',{id:r.id,allow});await refresh()}catch(e){message(e.message)}};row.append(b)}}$('requests').append(row)}
 $('devices').replaceChildren();for(const d of current.devices){const row=item(d.name,(d.online?'在线':'暂时离线')+' · 最近联系 '+new Date(d.lastSeen).toLocaleString());row.firstChild.className=d.online?'online':'offline';const b=el('button','撤销配对','secondary');b.onclick=async()=>{if(confirm('撤销这台手机的配对？其本地记录仍会保留。')){await request('/api/revoke',{deviceId:d.id});await refresh()}};row.append(b);$('devices').append(row)}if(!current.devices.length)$('devices').append(el('p','尚未连接手机，打开新版 App 会自动发出连接请求。'));
 const entries=current.entities.filter(e=>!e.deleted), tasks=entries.filter(e=>e.key.startsWith('task:')).sort((a,b)=>a.payload.scheduledAt.localeCompare(b.payload.scheduledAt));$('stats').textContent=tasks.length+' 项事项 · '+entries.filter(e=>!e.key.startsWith('task:')).length+' 条记录';$('tasks').replaceChildren();for(const e of tasks){const t=e.payload;const row=item(t.title,`${t.scheduledAt.replace('T',' ')} · ${t.durationMinutes} 分钟 · ${categories[t.category]} · ${statuses[t.status]} · 已记录 ${t.accumulatedSeconds||0} 秒`);if(!t.executionStartedAt&&!['COMPLETED','PARTIAL','SKIPPED'].includes(t.status)){const b=el('button','调整安排','secondary');b.onclick=()=>edit(t,e.revision);row.append(b)}$('tasks').append(row)}if(!tasks.length)$('tasks').append(el('p','配对后手机已有事项会回传，也可以在下方发布新计划。'));
 $('records').replaceChildren();for(const e of entries.filter(e=>!e.key.startsWith('task:')).slice(0,40)){const p=e.payload;$('records').append(item(p.title||'训练组次',e.key.startsWith('record:')?`${statuses[p.outcome]} · ${p.seconds} 秒 · ${p.result||'未填写备注'}`:`${p.sets.length} 组 · ${p.seconds} 秒 · ${p.simulated?'含模拟事件':'手工记录'}`))}
 }catch(e){$('health').textContent='电脑服务暂不可用'}}
$('rotate').onclick=async()=>{try{await request('/api/rotate-code',{});await refresh()}catch(e){message(e.message)}};
$('new-task').onclick=clearEditor;
$('editor').onsubmit=async event=>{event.preventDefault();if(!current)return;busy=true;const button=event.submitter;button.disabled=true;try{const task={...(editing||{}),id:editing?.id||crypto.randomUUID(),title:$('title').value.trim(),category:$('category').value,scheduledAt:$('scheduled').value,durationMinutes:Number($('duration').value),steps:$('steps').value,criteria:$('criteria').value,resourceUrl:$('resource-url').value.trim(),note:$('task-note').value,status:'PENDING',isDemo:false};if(task.resourceUrl&&!safeResource(task.resourceUrl))throw Error('资料需要有效的HTTP或HTTPS地址');await request('/api/publish',{basePlanVersion:current.planVersion,expectedRevisions:{[task.id]:editingRevision},tasks:[task]});message('电脑已保存，手机将在下次同步时接收；有冲突时需在手机确认。');clearEditor()}catch(e){message(e.message)}finally{busy=false;button.disabled=false;await refresh()}};
$('study-date').onchange=()=>{studySignature='';renderStudy()};
$('add-study').onclick=()=>{clearEditor();$('category').value='AI';$('scheduled').value=($('study-date').value||new Date().toLocaleDateString('sv-SE'))+'T09:45';$('editor-title').textContent='新增学习事项';$('editor').scrollIntoView({behavior:'smooth',block:'start'});$('title').focus()};
clearEditor();refresh();setInterval(refresh,2500);
