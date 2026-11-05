import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {registerHooks} from 'node:module'
import {Window} from 'happy-dom'
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc'

// Real Vue SFCs and published design-system/Element Plus DOM. No network or browser.
const window=new Window({url:'http://localhost/'})
for(const name of ['window','document','history','location','localStorage','sessionStorage','Storage','StorageEvent','navigator','HTMLElement','HTMLInputElement','HTMLTextAreaElement','HTMLButtonElement','SVGElement','Element','Node','Event','MouseEvent','KeyboardEvent','MutationObserver','ResizeObserver','CustomEvent','getComputedStyle','requestAnimationFrame','cancelAnimationFrame'])
  Object.defineProperty(globalThis,name,{value:typeof window[name]==='function'&&['getComputedStyle','requestAnimationFrame','cancelAnimationFrame'].includes(name)?window[name].bind(window):window[name],configurable:true,writable:true})
// Happy DOM eagerly creates finished promises even when the consumer never reads them.
// Observe only cancellation of this document's real virtual animations; no global error suppression.
const cancelledAnimations=[]
const animate=window.Element.prototype.animate
window.Element.prototype.animate=function(...args) {
  const animation=animate.apply(this,args)
  animation.finished.catch(error=>{if(error.name!=='AbortError')throw error;cancelledAnimations.push({element:this.tagName,name:error.name})})
  return animation
}
const hooks=registerHooks({load(url,context,next){
  if(/\.(?:png|svg|woff2?|ttf)$/.test(url))return {format:'module',source:'export default '+JSON.stringify(url),shortCircuit:true}
  if(url.endsWith('.css'))return {format:'module',source:'export default {}',shortCircuit:true}
  if(url.endsWith('.vue')) {
    const filename=fileURLToPath(url),{descriptor}=parse(readFileSync(filename,'utf8'),{filename})
    const script=compileScript(descriptor,{id:filename,inlineTemplate:Boolean(descriptor.scriptSetup),...(!descriptor.scriptSetup?{genDefaultAs:'__component'}:{})})
    const source=descriptor.scriptSetup?script.content:script.content+'\n'+compileTemplate({source:descriptor.template.content,filename,id:filename}).code+'\n__component.render=render;export default __component;'
    return {format:'module',source,shortCircuit:true}
  }
  return next(url,context)
}})
const Vue=await import('vue')
const {createI18n}=await import('vue-i18n')
const {ElConfigProvider}=await import('element-plus')
const {createMemoryHistory,createRouter}=await import('vue-router')
const DS=await import('@kestra-io/design-system')
const {default:ApplicationGroupCards}=await import('../src/ApplicationGroupCards.vue')
const {default:AccessList}=await import('../src/AccessList.vue')
const {nativeChips,matchesChips,readListQuery}=await import('../src/access-control-model.js')
const messages=Object.assign({},...['en','fr','de'].map(locale=>JSON.parse(readFileSync(new URL('../src/translations/'+locale+'.json',import.meta.url),'utf8'))))
const tick=async()=>{await Vue.nextTick();await new Promise(resolve=>setTimeout(resolve,20));await Vue.nextTick()}
const click=node=>{assert(node,'native control missing');assert(!node.disabled,'native control disabled');node.click()}
const shown=node=>{for(let current=node;current;current=current.parentElement)if(current.style.display==='none'||current.getAttribute('aria-hidden')==='true')return false;return true}

const {default:App}=await import('../src/App.vue')
const {createNavigationHooks}=await import('../src/navigation-lifecycle.js')
const response=(body,status=200)=>({ok:status<400,status,json:async()=>structuredClone(body)})
const wait=async(predicate,message)=>{for(let i=0;i<80;i++){if(predicate())return;await tick()}assert(predicate(),message)}
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
async function mountApp(requestOverride){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]})
 const hooks=createNavigationHooks(),requests=[],navigations=[]
 const state=Vue.shallowReactive({context:null})
 let capability={allowed:true,identityRead:true,identityManagement:true,userDirectory:true,subject:'alice',issuer:'issuer-a'},groupResponse=null,navigationWait=null
 const items=[{id:'kestra-a',displayName:'First',active:true,counts:{scopes:0,applications:0,subjects:0}},{id:'kestra-b',displayName:'Second',active:true,counts:{scopes:0,applications:0,subjects:0}}]
 const context={apiBase:'/api/v1/main',path:'application-groups',fullPath:'application-groups',tenant:'main',signal:new AbortController().signal,
  navigate:async(fullPath,options)=>{navigations.push(fullPath);assert(await hooks.beforeNavigate({extensionPath:fullPath.split('?')[0],fullPath,query:router.resolve('/'+fullPath).query}),'host beforeNavigate rejected query');if(navigationWait)await navigationWait(fullPath);state.context={...state.context,path:fullPath.split('?')[0],fullPath}},
  request:async(path,options={})=>{requests.push(path);const overridden=await requestOverride?.(path,options);if(overridden)return overridden;if(path.includes('/capabilities'))return response(capability);if(path.includes('/application-groups?'))return groupResponse?groupResponse():response({items,total:items.length,page:1,size:100});if(path.includes('/users?'))return response({items:[{subject:'alice',active:true}],total:1,page:1,size:100});throw new Error('Unexpected request '+path)}
 }
 state.context=context
 const container=document.createElement('div');document.body.append(container)
 const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h(App,{context:state.context}))})
 for(const [name,component] of Object.entries(DS))if(name.startsWith('Ks')&&component&&typeof component==='object')app.component(name,component)
 const i18n=createI18n({legacy:false,locale:'en',messages});await DS.registerDesignSystemI18n(i18n);app.use(i18n);app.use(router);app.provide('rbac-portals',document.body);app.provide('rbac-navigation-hooks',hooks);app.mount(container)
 await wait(()=>container.querySelector('.customize-button'),'App not loaded')
 return {container,router,hooks,state,requests,navigations,items,setNavigationWait:f=>{navigationWait=f},setCapability:c=>{capability=c},setGroupResponse:f=>{groupResponse=f},capability:()=>capability,close(){app.unmount();container.remove()}}
}
async function openAppAdvanced(view){click(view.container.querySelector('.customize-button'));await tick();const starter=document.querySelector('.advanced-add');if(starter)click(starter);await tick();return document.querySelector('.adv-builder')}
const inputText=(input,value)=>{assert(input,'native text input absent');input.focus();input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}))}
test('App native query echo preserves advanced instance, focus and multiple conditions through full authorized reload',async()=>{
 const v=await mountApp()
 try{
  const dialog=await openAppAdvanced(v);assert(dialog);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  const input=dialog.querySelector('.cond-value-input input');inputText(input,'kestra');await wait(()=>v.navigations.length>0,'native query did not reach host');await tick()
  assert(document.querySelector('.adv-builder')===dialog,'host context query echo must not unmount native filter')
  assert(document.activeElement===input,'query echo must preserve input focus');assert(v.requests.filter(p=>p.includes('/capabilities')).length>=2,'query must recheck capabilities')
  click(dialog.querySelector('.adv-footer .adv-add'));await tick();assert.equal(dialog.querySelectorAll('.adv-row').length,2)
  const field=dialog.querySelectorAll('.adv-row')[1].querySelector('.cond-field');click(field.querySelector('.kel-select__wrapper'));await tick();const option=[...document.querySelectorAll('[role="option"]')].find(n=>n.textContent.trim()==='Application group ID');assert(option);click(option);await tick()
  const inputs=dialog.querySelectorAll('.cond-value-input input');inputText(inputs[1],'kestra-a');await tick();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','query refresh incomplete')
  assert(document.querySelector('.adv-builder')===dialog);assert.equal(dialog.querySelectorAll('.adv-row').length,2)
 }finally{v.close()}
})
test('App consecutive native queries supersede slow reads and host echo keeps latest URL and data',async()=>{
 const v=await mountApp();const old=deferred(),second=deferred();let calls=0
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  v.setGroupResponse(()=>++calls===1?old.promise:calls===2?second.promise:response({items:v.items,total:2,page:1,size:100}))
  const input=dialog.querySelector('.cond-value-input input');inputText(input,'kes');await wait(()=>calls===1,'slow query did not start');inputText(input,'kestra-a');await wait(()=>calls>=2,'second native input blocked by query refresh')
  old.resolve(response({items:[{...v.items[0],id:'stale-secret'}],total:1,page:1,size:100}));await tick()
  assert.equal(v.container.querySelector('main').getAttribute('aria-busy'),'true','newer request must still be pending')
  inputText(input,'kestra-b');await wait(()=>calls===3,'stale finally blocked third native input during pending second read')
  await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','latest query incomplete')
  second.resolve(response({items:[{...v.items[0],id:'second-stale'}],total:1,page:1,size:100}));await tick()
  assert(document.querySelector('.adv-builder')===dialog);assert(v.state.context.fullPath.includes('kestra-b'));assert(!v.container.textContent.includes('stale-secret'));assert(!v.container.textContent.includes('second-stale'))
 }finally{old.resolve(response({items:[],total:0,page:1,size:100}));second.resolve(response({items:[],total:0,page:1,size:100}));v.close()}
})

for(const mode of ['identity','permission','403','503'])test('App query authorization '+mode+' clears old filter and sensitive rows; dependencies remain retryable',async()=>{
 const v=await mountApp()
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  if(mode==='identity')v.setCapability({...v.capability(),subject:'bob'})
  if(mode==='permission')v.setCapability({...v.capability(),identityManagement:false})
  if(mode==='403'||mode==='503')v.setGroupResponse(()=>response({code:mode==='403'?'access_denied':'authorization_unavailable'},Number(mode)))
  inputText(dialog.querySelector('.cond-value-input input'),'kestra');await wait(()=>v.navigations.length>0,'query echo absent');await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','reload unfinished')
  assert(!document.querySelector('.adv-builder'),'changed authorization or failed read must unmount old popup')
  if(mode==='403'||mode==='503'){
   assert(v.container.querySelector('[data-test="request-error"]'));assert(!v.container.querySelector('[data-test="group-card"]'),'old authorized rows must be cleared')
   if(mode==='503'){v.setGroupResponse(null);click(v.container.querySelector('[data-test="request-error"] button'));await wait(()=>v.container.querySelector('.customize-button'),'dependency retry did not reload')}
  }else assert(v.container.querySelector('.customize-button'),'allowed fresh identity/capabilities must complete full remount')
 }finally{v.close()}
})
test('App host query history, explicit refresh, tenant/path changes and malformed query keep authorization reloads',async()=>{
 const v=await mountApp()
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick();inputText(dialog.querySelector('.cond-value-input input'),'kestra-a');await wait(()=>v.navigations.length>0,'echo missing');await tick()
  const filtered=v.state.context.fullPath
  v.state.context={...v.state.context,fullPath:'application-groups'};await tick();await wait(()=>!Object.keys(v.router.currentRoute.value.query).length,'host history clear missing');assert(document.querySelector('.adv-builder')===dialog)
  v.state.context={...v.state.context,fullPath:filtered};await tick();await wait(()=>v.router.currentRoute.value.fullPath.includes('kestra-a'),'host forward query missing');assert(document.querySelector('.adv-builder')===dialog)
  click(dialog.querySelector('.adv-header-close'));await tick();const trigger=v.container.querySelector('.customize-button')
  const refreshes=v.container.querySelectorAll('.refresh-button');assert.equal(refreshes.length,1,'native refresh control absent/ambiguous');const refresh=refreshes[0]
  const capabilitiesBefore=v.requests.filter(p=>p.includes('/capabilities')).length
  click(refresh);await tick();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','explicit refresh incomplete')
  assert(v.requests.filter(p=>p.includes('/capabilities')).length>capabilitiesBefore,'refresh must recheck capabilities')
  assert(v.container.querySelector('.customize-button')!==trigger,'explicit refresh must remount the native filter')
  const afterRefresh=v.container.querySelector('.customize-button')
  // Host full path reload through a tenant change must clear the previous instance.
  v.state.context={...v.state.context,tenant:'other'};await tick();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','tenant reload missing');assert(v.container.querySelector('.customize-button')!==afterRefresh)
  const readsBeforeInvalid=v.requests.length
  v.state.context={...v.state.context,path:'users',fullPath:'users?page=-1'};await tick();await wait(()=>v.container.querySelector('[data-test="request-error"] button'),'malformed query must show Clear');assert(!v.container.querySelector('.customize-button'));assert.equal(v.requests.length,readsBeforeInvalid,'invalid query must fail before capabilities/users/subject reads')
  click(v.container.querySelector('[data-test="request-error"] button'));await wait(()=>v.container.querySelector('.customize-button'),'invalid query clear did not reload')
  assert.equal(v.state.context.fullPath,'users');assert(v.requests.some(p=>p.includes('/users?')))
 }finally{v.close()}
})
test('App host acknowledgement ordering coalesces newer native query without reverting visible input or formal URL',async()=>{
 const v=await mountApp(),firstAck=deferred();let calls=0
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  v.setNavigationWait(()=>++calls===1?firstAck.promise:Promise.resolve())
  const input=dialog.querySelector('.cond-value-input input');inputText(input,'kes');await wait(()=>v.navigations.length===1,'first query not sent')
  inputText(input,'kestra-b');await tick();assert(v.router.currentRoute.value.fullPath.includes('kestra-b'),'second input must update native router while host acknowledgement pending')
  firstAck.resolve();await wait(()=>v.state.context.fullPath.includes('kestra-b'),'formal host URL must settle to latest native query');await tick()
  assert(v.router.currentRoute.value.fullPath.includes('kestra-b'));assert(document.querySelector('.adv-builder')===dialog);assert.equal(input.value,'kestra-b')
 }finally{firstAck.resolve();v.close()}
})
for(const change of ['path','tenant','dispose'])test('App pending query acknowledgement cannot restore obsolete navigation after '+change,async()=>{
 const v=await mountApp(),ack=deferred()
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick();v.setNavigationWait(()=>ack.promise)
  inputText(dialog.querySelector('.cond-value-input input'),'kes');await wait(()=>v.navigations.length===1,'pending ack not reached')
  inputText(dialog.querySelector('.cond-value-input input'),'kestra-b');await tick()
  if(change==='dispose'){v.close();ack.resolve();await tick();assert.equal(v.navigations.length,1);return}
  v.state.context={...v.state.context,...(change==='path'?{path:'users',fullPath:'users'}:{tenant:'other'})}
  await tick();ack.resolve();await tick();await tick()
  assert.equal(v.state.context.fullPath,change==='path'?'users':'application-groups','old query ack must not restore stale destination')
  if(change==='tenant')assert.equal(v.state.context.tenant,'other')
  assert(!v.navigations.slice(1).some(p=>p.includes('kestra-b')),'obsolete queued query was sent')
 }finally{ack.resolve();if(change!=='dispose')v.close()}
})
test('App rejected host query navigation releases acknowledgement and same-target real history reloads',async()=>{
 const v=await mountApp()
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  v.setNavigationWait(()=>Promise.reject(Object.assign(new Error('navigation unavailable'),{status:503})))
  inputText(dialog.querySelector('.cond-value-input input'),'kestra-a');await wait(()=>v.container.querySelector('[data-test="request-error"]'),'navigate rejection must remain visible')
  const rejected=v.navigations[0];v.setNavigationWait(null)
  v.state.context={...v.state.context,fullPath:rejected};await tick();await wait(()=>v.router.currentRoute.value.fullPath.includes('kestra-a'),'real same-target host history was ignored after rejection')
  assert(!v.container.querySelector('[data-test="request-error"]'))
 }finally{v.close()}
})
// Exercise actual App reads, native permission rows, router canonicalization and host echo.
for(const mode of ['revoked','partial','missing','disabled','invalid'])test('App subject deep link revalidates '+mode+' without broadening rows',async()=>{
 let granted=['scope-a','scope-b'],active=true,missing=false
 const permissions=[{key:'permit-a',name:'Permit A',scopeKeys:['scope-a'],details:'A'},{key:'permit-b',name:'Permit B',scopeKeys:['scope-b'],details:'B'},{key:'outside',name:'Outside',scopeKeys:['scope-other'],details:'Other'}]
 const page=items=>response({items:items.map(item=>({...item,parentRevision:1})),total:items.length,page:1,size:100,directoryRevision:1,parentRevision:1})
 const v=await mountApp(path=>{
  if(path.includes('/scope-grants/detail?'))return missing?response({code:'not_found'},404):response({subject:'alice',scopeKeys:granted})
  if(path.includes('/users/detail?'))return response({subject:'alice',active})
  if(path.endsWith('/credentials'))return response({})
  if(path.includes('/permissions?'))return page(permissions)
  if(path.endsWith('/applications/~Y2xpZW50'))return response({clientId:'client',implementationKind:'external',revision:1})
  if(path.includes('/applications?'))return page([{clientId:'client',active:true,supportedScopeKeys:['scope-a','scope-b']}])
  if(path.includes('/scopes?'))return page(['scope-a','scope-b','scope-other'].map(key=>({key,name:key})))
  if(path.endsWith('/application-groups/kestra-a'))return response({id:'kestra-a',issuer:'issuer-a',revision:1})
 })
 const base='application-groups/kestra-a/applications/~Y2xpZW50'
 const original=base+'?filterSubject=alice&scopeKeys=scope-a&scopeKeys=scope-b'
 const enter=fullPath=>{v.state.context={...v.state.context,path:base,fullPath}}
 const settled=async()=>{await tick();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','subject read unfinished');await tick()}
 const rows=()=>[...v.container.querySelectorAll('[data-test="permission-row"]')].map(n=>n.dataset.permissionKey)
 try{
  enter(original);await settled();assert.deepEqual(rows(),['permit-a','permit-b'])
  granted=mode==='partial'?['scope-b']:[];active=mode!=='disabled';missing=mode==='missing'
  const before=v.requests.length
  if(mode==='invalid')enter(base+'?filterSubject=alice&filterSubject=bob&scopeKeys=scope-a')
  else {const refresh=v.container.querySelector('[data-test="application-permissions"] .refresh-button')||[...v.container.querySelectorAll('[data-test="application-permissions"] button')].find(n=>/refresh/i.test(n.getAttribute('aria-label')||n.textContent));click(refresh)}
  await settled()
  if(mode==='invalid'){assert(v.container.querySelector('[data-test="request-error"]'));assert.equal(v.requests.length,before);assert.deepEqual(rows(),[]);return}
  assert(v.requests.slice(before).some(p=>p.includes('/scope-grants/detail?subject=alice')),'refresh must reread grant')
  if(mode==='missing'||mode==='disabled'){assert(v.container.querySelector('[data-test="request-error"]'));assert.deepEqual(rows(),[]);return}
  assert(v.requests.slice(before).some(p=>p.includes('/users/detail?subject=alice')),'refresh must reread account')
  const expected=mode==='partial'?'scope-b':undefined
  assert.equal(v.router.currentRoute.value.query['filters[scopeKeys][IN]'],expected,'expired scope intersection must move into the native filter query')
  assert.equal(v.router.currentRoute.value.query.filterSubject,'alice')
  await wait(()=>v.state.context.fullPath===v.router.currentRoute.value.fullPath.slice(1),'canonical URL must reach host echo')
  assert.deepEqual(rows(),mode==='partial'?['permit-b']:[])
  // Fresh context and stale host history must both revalidate rather than restore old grants.
  const canonical=v.state.context.fullPath
  for(const destination of [base+'?filterSubject=alice',original,canonical]){enter(destination);await settled();assert.deepEqual(rows(),destination===base+'?filterSubject=alice'?[]:mode==='partial'?['permit-b']:[]);assert.equal(v.router.currentRoute.value.query.filterSubject,'alice');assert(!v.state.context.fullPath.includes('scope-a'))}
 }finally{v.close()}
})
for(const kind of ['group','scope','application'])test('App native box tab cancel and same-target retry preserves '+kind+' draft and controlled active',async()=>{
 const page=items=>response({items,total:items.length,page:1,size:100,directoryRevision:1,parentRevision:1})
 const v=await mountApp(path=>{
  if(path.endsWith('/application-groups/kestra-a'))return response({id:'kestra-a',displayName:'First',issuer:'issuer-a',active:true,revision:1})
  if(path.includes('/scopes?'))return page([{key:'scope-a',name:'Scope A',description:'',revision:1}])
  if(path.includes('/applications?'))return page([{clientId:'app-a',description:'',revision:1}])
  if(path.endsWith('/applications/~YXBwLWE'))return response({clientId:'app-a',description:'',revision:1,supportedScopeKeys:[]})
  if(path.includes('/permissions?'))return page([])
  if(path.endsWith('/credentials'))return response({clientId:'app-a',clientType:'PUBLIC',rotationMode:'NONE',secretSet:false,credentialRevision:1})
  if(path.includes('/scope-grants?'))return page([])
 })
 const base='application-groups/kestra-a',source=kind==='group'?'overview':kind==='scope'?'scopes':'applications',target=source==='overview'?'scopes':'overview'
 // 优化：切换到 scopes/applications tab 时，直接导航到第一个项的详情页
 const initial=base+(source==='overview'?'':('/'+source+(source==='scopes'?'/scope-a':source==='applications'?'/~YXBwLWE':'')))
 const visibleDialog=()=>[...document.querySelectorAll('[data-test="discard-confirm"]')].find(shown)
 const tab=name=>v.container.querySelector('[role="tab"][id="tab-'+name+'"]')
 try{
  v.state.context={...v.state.context,path:initial,fullPath:initial};
  // 对于 scopes/applications tab，初始在详情页，需要等待详情内容加载
  const inlineEntry=()=>kind==='group'?v.container.querySelector('[data-test="group-edit"]'):[...v.container.querySelectorAll('[data-test="'+kind+'-detail"] button')].find(node=>node.textContent.trim()==='Edit')
  await wait(()=>inlineEntry(),'inline entry absent')
  click(inlineEntry());await tick()
  const form=v.container.querySelector('.rbac-inline-form'),input=form.querySelector('input');inputText(input,'changed-draft');await tick()
  const destination=tab(target);assert(destination);destination.focus();click(destination)
  await wait(()=>visibleDialog(),'first tab click must confirm discard')
  click([...visibleDialog().querySelectorAll('button')].find(n=>n.textContent.trim()==='Cancel'))
  await wait(()=>!visibleDialog()&&document.activeElement===destination,'cancel must finish native dialog leave and restore trigger focus')
  assert(!visibleDialog(),'cancel must close visible dialog');assert.equal(input.value,'changed-draft');assert.equal(v.state.context.fullPath,initial)
  assert.equal(document.activeElement,destination,'cancel must restore trigger focus')
  click(destination);await wait(()=>visibleDialog(),'same target second click must reopen visible discard dialog')
  assert.equal(tab(source).getAttribute('aria-selected'),'true','cancelled navigation must retain controlled active tab')
  assert.equal(tab(target).getAttribute('aria-selected'),'false')
  click([...visibleDialog().querySelectorAll('button')].find(n=>n.textContent.trim()==='Discard changes'))
  const final=base+(target==='overview'?'':('/'+target+(target==='scopes'?'/scope-a':target==='applications'?'/~YXBwLWE':'')))
  await wait(()=>v.state.context.fullPath===final&&v.container.querySelector('main').getAttribute('aria-busy')==='false','confirmed tab must reach authorized host destination')
  await tick();assert.equal(tab(target).getAttribute('aria-selected'),'true');assert(!v.container.querySelector('.rbac-inline-form'));assert(!visibleDialog());assert.equal(document.activeElement,tab(target),'confirmed navigation must restore tab trigger focus')
  // Normal native tab change must still synchronize the host URL after discard.
  click(tab(source));await wait(()=>v.state.context.fullPath===initial,'normal tab must navigate');await tick();assert.equal(tab(source).getAttribute('aria-selected'),'true')
 }finally{v.close()}
})
test('App native box tabs retain active and host URL during real editor save and one-time credential',async()=>{
 const receipt=deferred();let saving=false
 const v=await mountApp((path,options)=>{
  if(path.endsWith('/users')&&options.method==='POST'){saving=true;return receipt.promise}
 })
 const dialog=()=>[...document.querySelectorAll('[data-test="temporary-password-once"]')].find(shown)
 const tab=name=>v.container.querySelector('[role="tab"][id="tab-'+name+'"]')
 try{
  click(tab('users'));await wait(()=>v.state.context.fullPath==='users'&&v.container.querySelector('main').getAttribute('aria-busy')==='false','users tab not loaded')
  click([...v.container.querySelectorAll('button')].find(n=>/Create/.test(n.textContent)));await wait(()=>document.querySelector('[data-test="drawer-user"] input[name="username"]'),'user drawer absent')
  inputText(document.querySelector('[data-test="drawer-user"] input[name="username"]'),'tab-lock-account')
  document.querySelector('[data-test="drawer-user"] form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait(()=>saving,'real editor POST not started')
  click(tab('application-groups'));await tick();assert.equal(v.state.context.fullPath,'users');assert.equal(tab('users').getAttribute('aria-selected'),'true');assert.equal(await v.hooks.beforeNavigate({fullPath:'application-groups'}),false,'saving must block external host navigation')
  const reads=v.requests.filter(path=>path.includes('/capabilities')).length
  v.state.context={...v.state.context,fullPath:'users?page=2'};await tick()
  assert.equal(v.router.currentRoute.value.query.page,undefined)
  assert.equal(v.requests.filter(path=>path.includes('/capabilities')).length,reads,'preserveView must not bypass editor.saving')
  assert(document.querySelector('[data-test="drawer-user"]'))
  v.state.context={...v.state.context,fullPath:'users'};await tick()
  receipt.resolve(response({subject:'tab-lock-account',revision:1,temporaryPassword:'isolated-component-test-value'}));await wait(()=>dialog()&&v.container.querySelector('main').getAttribute('aria-busy')==='false','one-time dialog absent')
  click(tab('application-groups'));await tick();assert.equal(v.state.context.fullPath,'users');assert.equal(tab('users').getAttribute('aria-selected'),'true');assert(dialog());assert.equal(await v.hooks.beforeNavigate({fullPath:'application-groups'}),false,'oneTime must block external host navigation')
  click([...dialog().querySelectorAll('button')].find(n=>n.textContent.trim()==='Close'));await tick()
  click(tab('application-groups'));await wait(()=>v.state.context.fullPath==='application-groups','closing oneTime must restore normal tab navigation');await tick();assert.equal(tab('application-groups').getAttribute('aria-selected'),'true')
 }finally{receipt.resolve(response({subject:'tab-lock-account',revision:1,temporaryPassword:'isolated-component-test-value'}));v.close()}
})
test('App slow subject query retains real credential-write and oneTime navigation locks',async()=>{
 const grant=deferred(),rotation=deferred();let slow=false,grantStarted=false,rotationStarted=false,credentialRevision=1
 const page=items=>response({items,total:items.length,page:1,size:100,directoryRevision:1,parentRevision:1})
 const v=await mountApp((path,options)=>{
  if(path.endsWith('/credentials/rotate')){rotationStarted=true;return rotation.promise}
  if(path.endsWith('/credentials'))return response({clientId:'client',clientType:'confidential',secretSet:true,maskedHint:'test-only',credentialRevision,rotationMode:'online'})
  if(path.includes('/scope-grants/detail?')){if(slow){grantStarted=true;return grant.promise}return response({subject:'alice',scopeKeys:['scope-a']})}
  if(path.includes('/users/detail?'))return response({subject:'alice',active:true})
  if(path.includes('/permissions?'))return page([{key:'permit-a',name:'Permit A',scopeKeys:['scope-a'],details:'A',parentRevision:1}])
  if(path.endsWith('/applications/~Y2xpZW50'))return response({clientId:'client',implementationKind:'external',revision:1})
  if(path.includes('/applications?'))return page([{clientId:'client',active:true,supportedScopeKeys:['scope-a']}])
  if(path.includes('/scopes?'))return page([{key:'scope-a',name:'A'}])
  if(path.endsWith('/application-groups/kestra-a'))return response({id:'kestra-a',issuer:'issuer-a',revision:1})
 })
 const base='application-groups/kestra-a/applications/~Y2xpZW50',initial=base+'?filterSubject=alice&scopeKeys=scope-a'
 const dialog=()=>[...document.querySelectorAll('[data-test="credential-once"]')].find(shown)
 const overview=()=>v.container.querySelector('[role="tab"][id="tab-overview"]')
 try{
  v.state.context={...v.state.context,path:base,fullPath:initial};await wait(()=>v.container.querySelector('[data-test="credential-rotate"]')&&v.container.querySelector('[data-test="permission-row"]'),'subject application absent')
  slow=true;v.state.context={...v.state.context,fullPath:initial+'&page=2'};await wait(()=>grantStarted,'slow same-path subject read absent')
  assert.equal(v.container.querySelector('main').getAttribute('aria-busy'),'true')
  // Public credential panel remains mounted during the same-path read; no private state injection.
  click(v.container.querySelector('[data-test="credential-rotate"]'));await tick()
  const confirmRotate=document.querySelector('[data-test="credential-rotate-confirm"]');assert(confirmRotate,'rotation risk confirmation absent');click(confirmRotate)
  await wait(()=>rotationStarted,'real rotation POST absent')
  click(overview());await tick();assert.equal(v.state.context.path,base);assert.equal(await v.hooks.beforeNavigate({fullPath:'users'}),false,'credential write must block external navigation during query read')
  const reads=v.requests.filter(path=>path.includes('/capabilities')).length
  v.state.context={...v.state.context,path:'users',fullPath:'users'};await tick()
  assert.equal(v.router.currentRoute.value.path,'/'+base,'queryRefreshing must not bypass credential write')
  assert.equal(v.requests.filter(path=>path.includes('/capabilities')).length,reads)
  v.state.context={...v.state.context,path:base,fullPath:initial+'&page=2'};await tick()
  credentialRevision=2;rotation.resolve(response({clientId:'client',credentialRevision:2,clientSecret:'isolated-component-test-secret'}));await wait(()=>dialog(),'real rotation oneTime absent')
  assert.equal(v.container.querySelector('main').getAttribute('aria-busy'),'true','subject read must remain pending')
  click(overview());await tick();assert.equal(v.state.context.path,base);assert.equal(v.container.querySelector('[role="tab"][id="tab-applications"]').getAttribute('aria-selected'),'true');assert.equal(await v.hooks.beforeNavigate({fullPath:'users'}),false,'oneTime must block external navigation during query read')
  v.state.context={...v.state.context,path:'users',fullPath:'users'};await tick()
  assert.equal(v.router.currentRoute.value.path,'/'+base,'queryRefreshing must not bypass oneTime')
  assert(dialog());assert.equal(v.requests.filter(path=>path.includes('/capabilities')).length,reads)
  v.state.context={...v.state.context,path:base,fullPath:initial+'&page=2'};await tick()
  click([...dialog().querySelectorAll('button')].find(n=>n.textContent.trim()==='Close'));await tick()
  grant.resolve(response({subject:'alice',scopeKeys:[]}));await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false'&&!v.state.context.fullPath.includes('scopeKeys'),'released subject read must normalize host URL')
  assert.equal(v.router.currentRoute.value.query.filterSubject,'alice');assert.equal(v.container.querySelectorAll('[data-test="permission-row"]').length,0)
 }finally{grant.resolve(response({subject:'alice',scopeKeys:[]}));rotation.resolve(response({clientId:'client',credentialRevision:2,clientSecret:'isolated-component-test-secret'}));v.close()}
})
test('App context watcher releases only read-refresh busy and supersedes stale responses on a different path',async()=>{
 const pending=deferred(),v=await mountApp();let started=false
 try{
  v.setGroupResponse(()=>{started=true;return pending.promise})
  v.state.context={...v.state.context,fullPath:'application-groups?size=10'}
  await wait(()=>started&&v.container.querySelector('main').getAttribute('aria-busy')==='true','read refresh did not start')
  assert.equal(await v.hooks.beforeNavigate({fullPath:'users'}),true)
  v.state.context={...v.state.context,path:'users',fullPath:'users'}
  await wait(()=>v.container.querySelector('[data-test="user-row"]')&&v.container.querySelector('main').getAttribute('aria-busy')==='false','context watcher blocked read-only navigation')
  pending.resolve(response({items:[{id:'stale-group',displayName:'Stale secret'}],total:1,page:1,size:100}));await tick()
  assert.equal(v.router.currentRoute.value.path,'/users');assert(!v.container.textContent.includes('Stale secret'))
 }finally{pending.resolve(response({items:[],total:0,page:1,size:100}));v.close()}
})

test('App dirty inline draft during query refresh still requires explicit discard for host context navigation',async()=>{
 const pending=deferred();let slow=false,started=false
 const v=await mountApp(path=>{
  if(path.endsWith('/application-groups/kestra-a')){if(slow){started=true;return pending.promise}return response({id:'kestra-a',displayName:'First',description:'Original',issuer:'issuer-a',revision:1})}
 })
 try{
  v.state.context={...v.state.context,path:'application-groups/kestra-a',fullPath:'application-groups/kestra-a'}
  await wait(()=>v.container.querySelector('[data-test="group-edit"]')&&v.container.querySelector('main').getAttribute('aria-busy')==='false','group detail did not load')
  slow=true;v.state.context={...v.state.context,fullPath:'application-groups/kestra-a?size=10'}
  await wait(()=>started,'read refresh did not start')
  click(v.container.querySelector('[data-test="group-edit"]'));await tick()
  inputText(v.container.querySelector('.rbac-inline-form textarea'),'Protected dirty draft')
  const reads=v.requests.filter(path=>path.includes('/capabilities')).length
  v.state.context={...v.state.context,path:'users',fullPath:'users'};await tick()
  const dialog=[...document.querySelectorAll('[data-test="discard-confirm"]')].find(shown);assert(dialog,'read busy bypass must still show discard confirmation')
  assert.equal(v.router.currentRoute.value.path,'/application-groups/kestra-a')
  assert.equal(v.requests.filter(path=>path.includes('/capabilities')).length,reads)
  assert.equal(v.container.querySelector('.rbac-inline-form textarea').value,'Protected dirty draft')
  pending.resolve(response({id:'kestra-a',displayName:'First',description:'Original',issuer:'issuer-a',revision:1}))
  await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','read refresh did not finish')
  click([...dialog.querySelectorAll('button')].find(node=>node.textContent.trim()==='Cancel'));await tick()
  assert.equal(v.container.querySelector('.rbac-inline-form textarea').value,'Protected dirty draft')
  assert.equal(v.router.currentRoute.value.path,'/application-groups/kestra-a')
 }finally{pending.resolve(response({id:'kestra-a',displayName:'First',description:'Original',issuer:'issuer-a',revision:1}));v.close()}
})

const tabPage=(items,revision=1,page=1,total=items.length)=>response({items,total,page,size:100,directoryRevision:revision,parentRevision:1})
async function mountTabApp(override) {
 const view=await mountApp(async(path,options)=>{
  const result=await override?.(path,options)
  if(result)return result
  if(path.endsWith('/application-groups/kestra-a'))return response({id:'kestra-a',displayName:'Protected group',issuer:'issuer-a',revision:1})
  if(path.includes('/scopes?'))return tabPage([{key:'scope-a',name:'Scope A',revision:1}])
  if(path.includes('/applications?'))return tabPage([{clientId:'app-a',description:'Application A',revision:1}])
  if(path.endsWith('/applications/~YXBwLWE'))return response({clientId:'app-a',description:'Application A',implementationKind:'external-declaration',revision:1})
  if(path.includes('/permissions?')||path.includes('/scope-grants?'))return tabPage([])
  if(path.endsWith('/credentials'))return response({clientId:'app-a',clientType:'PUBLIC',rotationMode:'NONE',secretSet:false,credentialRevision:1})
 })
 try {
  view.state.context={...view.state.context,path:'application-groups/kestra-a',fullPath:'application-groups/kestra-a'}
  await wait(()=>view.container.querySelector('[data-test="group-edit"]')&&view.container.querySelector('main').getAttribute('aria-busy')==='false','group detail did not load')
  return view
 }catch(error){view.close();throw error}
}
const nativeTab=(view,target)=>view.container.querySelector('[role="tab"][id="tab-'+target+'"]')
const tabSettled=view=>view.container.querySelector('main').getAttribute('aria-busy')==='false'
for(const target of ['scopes','applications'])for(const empty of [false,true])test('App '+target+' tab prefetch navigates exactly once to '+(empty?'empty list':'canonical detail'),async()=>{
 const pending=deferred();let pause=true,started=false
 const view=await mountTabApp(path=>{
  if(path.includes('/'+target+'?')&&pause){started=true;return pending.promise}
  if(empty&&path.includes('/'+target+'?'))return tabPage([])
 })
 try {
  const detail=view.container.querySelector('[data-test="group-detail"]')
  click(nativeTab(view,target));await wait(()=>started,'prefetch did not start')
  assert.equal(view.navigations.length,0);assert.equal(view.container.querySelector('[data-test="group-detail"]'),detail)
  assert.equal(nativeTab(view,'overview').getAttribute('aria-selected'),'true')
  click(nativeTab(view,target));await tick();assert.equal(view.requests.filter(path=>path.includes('/'+target+'?')).length,1,'busy must deduplicate tab clicks')
  pause=false;pending.resolve(tabPage(empty?[]:target==='scopes'?[{key:'scope-a',name:'Scope A',revision:1}]:[{clientId:'app-a',revision:1}]))
  const destination='application-groups/kestra-a/'+target+(empty?'':target==='scopes'?'/scope-a':'/~YXBwLWE')
  await wait(()=>view.state.context.fullPath===destination&&tabSettled(view),'direct tab navigation did not settle');await tick()
  assert.deepEqual(view.navigations,[destination]);assert.equal(nativeTab(view,target).getAttribute('aria-selected'),'true')
  if(!empty)assert(view.container.querySelector('[data-test="'+(target==='scopes'?'scope':'application')+'-detail"]'))
 }finally{pending.resolve(tabPage([]));view.close()}
})
for(const mode of ['parent','second-page'])test('App tab prefetch rejects '+mode+' revision conflict without navigating and permits reload/retry',async()=>{
 let conflict=true
 const view=await mountTabApp(path=>{
  if(!path.includes('/scopes?'))return
  const page=Number(new URL(path,'http://localhost').searchParams.get('page'))
  if(!conflict)return tabPage([{key:'scope-a',name:'Scope A',revision:1}])
  if(mode==='parent')return tabPage([{key:'stale-scope'}],2)
  return page===1?tabPage([{key:'stale-scope'}],1,1,2):tabPage([{key:'other-scope'}],2,2,2)
 })
 try {
  click(nativeTab(view,'scopes'));await wait(()=>view.container.querySelector('[data-test="conflict-refresh"]')&&tabSettled(view),'revision conflict must remain retryable')
  assert.deepEqual(view.navigations,[]);assert(!view.container.querySelector('[data-test="scope-detail"]'))
  if(mode==='second-page')assert(view.requests.some(path=>path.endsWith('/scopes?page=2&size=100')))
  conflict=false;click(view.container.querySelector('[data-test="conflict-refresh"]'))
  await wait(()=>tabSettled(view)&&!view.container.querySelector('[data-test="request-error"]'),'conflict reload stuck')
  click(nativeTab(view,'scopes'));await wait(()=>view.state.context.fullPath.endsWith('/scopes/scope-a')&&tabSettled(view),'retry failed')
  assert.deepEqual(view.navigations,['application-groups/kestra-a/scopes/scope-a'])
 }finally{view.close()}
})
for(const mode of ['503','network','navigation'])test('App tab '+mode+' failure releases busy and same-tab retry navigates once',async()=>{
 let failing=true
 const view=await mountTabApp(path=>{
  if(failing&&path.includes('/scopes?')){
   if(mode==='503')return response({code:'unavailable'},503)
   if(mode==='network')throw new TypeError('Network unavailable')
  }
 })
 try {
  if(mode==='navigation')view.setNavigationWait(()=>Promise.reject(Object.assign(new Error('navigation unavailable'),{status:503})))
  click(nativeTab(view,'scopes'));await wait(()=>view.container.querySelector('[data-test="request-error"]')&&tabSettled(view),'failed prefetch/navigation retained busy')
  assert.equal(view.state.context.fullPath,'application-groups/kestra-a');assert.equal(view.navigations.length,mode==='navigation'?1:0)
  failing=false;view.setNavigationWait(null);view.navigations.length=0
  click(nativeTab(view,'scopes'));await wait(()=>view.state.context.fullPath.endsWith('/scopes/scope-a')&&tabSettled(view),'same-tab retry stuck')
  assert.deepEqual(view.navigations,['application-groups/kestra-a/scopes/scope-a'])
  assert(!view.container.querySelector('[data-test="request-error"]'))
 }finally{view.close()}
})
for(const mode of ['identity-before-list','identity-after-list','permission','denied'])test('App tab prefetch '+mode+' clears sensitive data and never navigates',async()=>{
 let capabilityReads=0
 const view=await mountTabApp(path=>{
  if(path.includes('/capabilities')&&capabilityReads){
   capabilityReads++
   if(mode==='identity-before-list'||mode==='identity-after-list'&&capabilityReads>=3)return response({...view.capability(),subject:'bob'})
   if(mode==='permission')return response({...view.capability(),identityRead:false})
   if(mode==='denied')return response({...view.capability(),allowed:false})
  }
 })
 try {
  capabilityReads=1;click(nativeTab(view,'scopes'))
  await wait(()=>view.container.querySelector('[data-test="sign-in-again"]')&&tabSettled(view),'identity/permission change must fail closed')
  assert.deepEqual(view.navigations,[]);assert(!view.container.textContent.includes('Protected group'))
  assert(!view.container.querySelector('[data-test="group-edit"]'));assert(!view.container.querySelector('[data-test="scope-detail"]'))
 }finally{view.close()}
})
for(const change of ['path','tenant','request','signal','abort','dispose'])test('App late tab list after '+change+' cannot navigate or overwrite a newer view',async()=>{
 const pending=deferred();let started=false
 const view=await mountTabApp(path=>{if(path.includes('/scopes?')){started=true;return pending.promise}})
 let closed=false
 try {
  click(nativeTab(view,'scopes'));await wait(()=>started,'slow list not started')
  if(change==='dispose'){view.close();closed=true}
  else if(change==='abort'){const controller=new AbortController();view.state.context={...view.state.context,signal:controller.signal};controller.abort()}
  else if(change==='path')view.state.context={...view.state.context,path:'users',fullPath:'users'}
  else if(change==='tenant')view.state.context={...view.state.context,tenant:'other'}
  else if(change==='request'){
   const request=view.state.context.request
   view.state.context={...view.state.context,request:(...args)=>request(...args)}
  }else view.state.context={...view.state.context,signal:new AbortController().signal}
  await tick();pending.resolve(tabPage([{key:'stale-sensitive-scope',name:'Old identity',revision:1}]))
  await tick();await tick()
  assert.deepEqual(view.navigations,[],'late list must not initiate old tab navigation')
  if(!closed){
   await wait(()=>tabSettled(view),'obsolete tab read retained busy')
   assert(!view.container.textContent.includes('stale-sensitive-scope'))
   assert.equal(view.state.context.fullPath,change==='path'?'users':'application-groups/kestra-a')
   if(change==='path')assert(view.container.querySelector('.customize-button'),'accepted new path must load users')
  }
 }finally{pending.resolve(tabPage([]));if(!closed)view.close()}
})

for(const mode of ['revision','field-order','read-only','manage-change'])test('App same-subject tab prefetch accepts '+mode+' without false authorization denial',async()=>{
 let changed=false
 const view=await mountTabApp(path=>{
  if(!changed||!path.includes('/capabilities'))return
  const value={...view.capability(),runtimePolicyRevision:2}
  if(mode==='read-only'||mode==='manage-change')value.identityManagement=false
  return response(mode==='field-order'?Object.fromEntries(Object.entries(value).reverse()):value)
 })
 try {
  if(mode==='read-only'){
   view.setCapability({...view.capability(),identityManagement:false,runtimePolicyRevision:1})
   view.state.context={...view.state.context,fullPath:'application-groups/kestra-a?size=10'}
   await wait(()=>tabSettled(view)&&!view.container.querySelector('[data-test="group-edit"]'),'read-only page did not reload')
  }
  changed=true;click(nativeTab(view,'scopes'))
  await wait(()=>view.state.context.fullPath.endsWith('/scopes/scope-a')&&tabSettled(view),'permitted metadata/permission change blocked navigation')
  assert.deepEqual(view.navigations,['application-groups/kestra-a/scopes/scope-a'])
  assert(!view.container.querySelector('[data-test="sign-in-again"]'));assert(!view.container.querySelector('[data-test="request-error"]'))
  assert(view.container.querySelector('[data-test="scope-detail"]'))
  if(mode==='read-only'||mode==='manage-change')assert(![...view.container.querySelectorAll('[data-test="scope-detail"] button')].some(node=>node.textContent.trim()==='Edit'))
 }finally{view.close()}
})
for(const preference of ['theme','locale','signal'])test('App '+preference+' context with new request wrapper preserves inline draft and leave guard',async()=>{
 const view=await mountTabApp()
 try {
  click(view.container.querySelector('[data-test="group-edit"]'));await tick()
  inputText(view.container.querySelector('.rbac-inline-form textarea'),'Protected wrapper draft');await tick()
  const form=view.container.querySelector('.rbac-inline-form'),reads=view.requests.length,request=view.state.context.request
  view.state.context={...view.state.context,request:(...args)=>request(...args),...(preference==='theme'?{theme:'dark'}:preference==='locale'?{locale:'zh_CN'}:{signal:new AbortController().signal})}
  await tick()
  assert.equal(view.container.querySelector('.rbac-inline-form'),form);assert.equal(form.querySelector('textarea').value,'Protected wrapper draft');assert.equal(view.requests.length,reads)
  click(nativeTab(view,'scopes'));await wait(()=>[...document.querySelectorAll('[data-test="discard-confirm"]')].some(shown),'wrapper replacement bypassed draft guard')
  assert.deepEqual(view.navigations,[])
 }finally{view.close()}
})
for(const preference of ['theme','locale'])test('App '+preference+' wrapper invalidates old tab prefetch without navigation or identity denial',async()=>{
 const pending=deferred();let started=false,pause=true
 const view=await mountTabApp(path=>{if(pause&&path.includes('/scopes?')){started=true;return pending.promise}})
 try {
  const detail=view.container.querySelector('[data-test="group-detail"]')
  click(nativeTab(view,'scopes'));await wait(()=>started,'prefetch did not start')
  const request=view.state.context.request
  view.state.context={...view.state.context,request:(...args)=>request(...args),...(preference==='theme'?{theme:'dark'}:{locale:'zh_CN'})}
  await wait(()=>tabSettled(view),'wrapper change did not release obsolete prefetch')
  pending.resolve(tabPage([{key:'obsolete-scope',name:'Obsolete',revision:1}]))
  await tick();await tick()
  assert.deepEqual(view.navigations,[]);assert.equal(view.container.querySelector('[data-test="group-detail"]'),detail)
  assert(!view.container.querySelector('[data-test="sign-in-again"]'));assert(!view.container.textContent.includes('obsolete-scope'))
  pause=false;click(nativeTab(view,'scopes'));await wait(()=>view.state.context.fullPath.endsWith('/scopes/scope-a')&&tabSettled(view),'fresh tab attempt did not recover')
  assert.deepEqual(view.navigations,['application-groups/kestra-a/scopes/scope-a'])
 }finally{pending.resolve(tabPage([]));view.close()}
})
for(const preference of ['theme','locale','signal'])test('App '+preference+' wrapper preserves actual one-time credential and its navigation lock',async()=>{
 const view=await mountApp((path,options)=>path.endsWith('/users')&&options.method==='POST'?response({subject:'wrapper-account',revision:1,temporaryPassword:'isolated-wrapper-secret'}):undefined)
 const dialog=()=>[...document.querySelectorAll('[data-test="temporary-password-once"]')].find(shown)
 try {
  click(nativeTab(view,'users'));await wait(()=>view.state.context.fullPath==='users'&&tabSettled(view),'users not loaded')
  click([...view.container.querySelectorAll('button')].find(node=>/Create/.test(node.textContent)))
  await wait(()=>document.querySelector('[data-test="drawer-user"] input[name="username"]'),'user drawer absent')
  inputText(document.querySelector('[data-test="drawer-user"] input[name="username"]'),'wrapper-account')
  document.querySelector('[data-test="drawer-user"] form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))
  await wait(()=>dialog()&&tabSettled(view),'actual one-time credential absent')
  const existing=dialog(),request=view.state.context.request,reads=view.requests.length
  view.state.context={...view.state.context,request:(...args)=>request(...args),...(preference==='theme'?{theme:'dark'}:preference==='locale'?{locale:'zh_CN'}:{signal:new AbortController().signal})}
  await tick()
  assert.equal(dialog(),existing);assert.equal(view.requests.length,reads)
  click(nativeTab(view,'application-groups'));await tick()
  assert.equal(view.state.context.fullPath,'users');assert.equal(dialog(),existing)
  assert.equal(await view.hooks.beforeNavigate({fullPath:'application-groups'}),false)
 }finally{view.close()}
})

test.after(()=>{hooks.deregister();window.happyDOM.abort()})
