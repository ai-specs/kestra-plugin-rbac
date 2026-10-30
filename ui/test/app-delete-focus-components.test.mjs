import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync,readdirSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {registerHooks} from 'node:module'
import {Window} from 'happy-dom'
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc'

// Real App, public design-system and published shadow renderer. HTTP is mocked;
// deferred replies model the reload race, not browser geometry or real auth.
const window=new Window({url:'http://localhost/'})
for(const name of ['window','document','history','location','localStorage','sessionStorage','Storage','StorageEvent','navigator','HTMLElement','HTMLInputElement','HTMLTextAreaElement','HTMLButtonElement','SVGElement','Element','Node','Event','MouseEvent','KeyboardEvent','MutationObserver','ResizeObserver','CustomEvent','getComputedStyle','requestAnimationFrame','cancelAnimationFrame'])
 Object.defineProperty(globalThis,name,{value:typeof window[name]==='function'&&['getComputedStyle','requestAnimationFrame','cancelAnimationFrame'].includes(name)?window[name].bind(window):window[name],configurable:true,writable:true})
const hooks=registerHooks({load(url,context,next){
 if(/\.(?:png|svg|woff2?|ttf)$/.test(url))return {format:'module',source:'export default '+JSON.stringify(url),shortCircuit:true}
 if(url.endsWith('.css'))return {format:'module',source:'export default {}',shortCircuit:true}
 if(url.endsWith('.vue')){
  const filename=fileURLToPath(url),{descriptor}=parse(readFileSync(filename,'utf8'),{filename})
  const script=compileScript(descriptor,{id:filename,inlineTemplate:Boolean(descriptor.scriptSetup),...(!descriptor.scriptSetup?{genDefaultAs:'__component'}:{})})
  const source=descriptor.scriptSetup?script.content:script.content+'\n'+compileTemplate({source:descriptor.template.content,filename,id:filename}).code+'\n__component.render=render;export default __component;'
  return {format:'module',source,shortCircuit:true}
 }
 return next(url,context)
}})
const Vue=await import('vue')
const {nodeOps,patchProp}=await import('@vue/runtime-dom')
const {ElConfigProvider,ID_INJECTION_KEY}=await import('element-plus')
const {createI18n}=await import('vue-i18n')
const {createMemoryHistory,createRouter}=await import('vue-router')
const {default:App}=await import('../src/App.vue')
const {default:KsDrawer}=await import('@kestra-io/design-system/components/Feedback/KsDrawer')
const {default:KsDialog}=await import('@kestra-io/design-system/components/Feedback/KsDialog')
const {KsButton,KsSelect,KsOption,registerDesignSystemI18n}=await import('@kestra-io/design-system')
const {createShadowOverlayScope,createShadowRenderer,shadowOverlayComponents}=await import('../../.integration/ui-sdk/shadow-overlays.mjs')
const messages=Object.assign({},...readdirSync(new URL('../src/translations/',import.meta.url)).filter(name=>name.endsWith('.json')).map(name=>JSON.parse(readFileSync(new URL('../src/translations/'+name,import.meta.url),'utf8'))))
const tick=async()=>{await Vue.nextTick();await new Promise(resolve=>setTimeout(resolve,0));await Vue.nextTick()}
const wait=async(predicate,message)=>{for(let i=0;i<80;i++){if(predicate())return;await tick()}assert(predicate(),message)}
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
const click=node=>{assert(node,'public control missing');if(!node.disabled)node.click()}
const response=(body,status=200)=>({ok:status<400,status,json:async()=>structuredClone(body)})

async function scenario({selected='a',deleteStatus=204,reloadStatus=200,navigationReject=false,manage=true}={}){
 const host=document.createElement('div');document.body.append(host)
 const root=host.attachShadow({mode:'open'}),content=document.createElement('div'),portal=document.createElement('div');root.append(content,portal)
 // happy-dom has no layout. Visibility is modeled only for the public SDK's
 // focus-ring algorithm; actual geometry remains a production-browser gate.
 const original=window.HTMLElement.prototype.getClientRects
 window.HTMLElement.prototype.getClientRects=function(){return this.closest('[hidden]')?[]:[{width:100,height:30}]}
 const controller=new AbortController(),scope=createShadowOverlayScope({container:host,content,portal,signal:controller.signal,fallback:()=>content.querySelector('[data-focus-fallback]')??content.querySelector('main')})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]})
 const state=Vue.shallowReactive({context:null}),requests=[],navigations=[]
 const scopes=['a','b'].map(key=>({key,name:key.toUpperCase(),description:'',revision:1}))
 let deleted=false,deleteWait=null,reloadWait=null,navigationWait=null,revoke=false
 const context={contractVersion:1,apiBase:'/api/v1/main',path:'application-groups/g/scopes/'+selected,fullPath:'application-groups/g/scopes/'+selected,tenant:'main',signal:controller.signal,
  navigate:async(target,options)=>{
   navigations.push({target,options});if(navigationWait)await navigationWait.promise
   if(navigationReject)throw Object.assign(new Error('canonical navigation rejected'),{status:503})
   state.context={...state.context,path:target.split('?')[0],fullPath:target};await Vue.nextTick()
  },request:async(path,options={})=>{
   const method=options.method??'GET';requests.push({path,method,body:options.body})
   if(method==='DELETE'){
    if(deleteWait)await deleteWait.promise
    if(deleteStatus!==204)return response({code:'revision_conflict'},deleteStatus)
    const key=decodeURIComponent(path.split('/').at(-1));scopes.splice(scopes.findIndex(row=>row.key===key),1);deleted=true
    return response(null,204)
   }
   if(deleted){if(reloadWait)await reloadWait.promise;if(reloadStatus!==200)return response({},reloadStatus)}
   if(path.includes('/capabilities'))return response({allowed:!revoke,identityRead:!revoke,identityManagement:manage&&!revoke,userDirectory:false,applicationGroupId:'g'})
   const relative=path.split('/application-groups/g')[1]??''
   if(relative==='')return response({id:'g',displayName:'Group',issuer:'issuer',revision:1,authorizationVersion:1})
   const items=relative.startsWith('/scopes')?scopes:[]
   return response({items,total:items.length,page:1,size:100,directoryRevision:1})
  }}
 state.context=context
 const renderer=createShadowRenderer({...Vue,nodeOps,patchProp},scope,{idInjectionKey:ID_INJECTION_KEY,namespace:'kel'})
 const app=renderer.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>[Vue.h(KsButton,{'data-test':'persistent-shell-trigger'},()=> 'Shell action'),Vue.h(App,{context:state.context})])})
 app.provide('rbac-portals',portal);app.use(router)
 app.component('KsButton',KsButton);app.component('KsOption',KsOption)
 for(const [name,component]of Object.entries(shadowOverlayComponents(Vue,{KsDrawer,KsDialog,KsSelect},scope)))app.component(name,component)
 const i18n=createI18n({legacy:false,locale:'en',messages});app.use(i18n);await registerDesignSystemI18n(i18n)
 app.mount(content)
 await wait(()=>content.querySelector('[data-test="scope-sidebar"]')&&content.querySelector('main').getAttribute('aria-busy')==='false','authorized initial sidebar did not load')
 const fallback=()=>content.querySelector('[data-focus-fallback]')
 const button=key=>[...content.querySelectorAll('[data-test="scope-sidebar"] li')].find(row=>row.querySelector('code')?.textContent===key)?.querySelector('[data-test="scope-delete"]')
 return {root,content,portal,requests,navigations,state,scope,button,fallback,
  holdDelete(){deleteWait=deferred();return deleteWait},holdReload(){reloadWait=deferred();return reloadWait},holdNavigation(){navigationWait=deferred();return navigationWait},revoke(){revoke=true},
  async open(key='a',{keepFocus=false}={}){const trigger=button(key);assert(trigger);if(!keepFocus)trigger.focus();click(trigger);await tick();assert(portal.querySelector('[data-test="delete-confirm"]'));return trigger},
  async confirm(){click(portal.querySelector('[data-test="delete-confirm"] .kel-button--danger'));await tick()},
  async close(){deleteWait?.resolve();reloadWait?.resolve();navigationWait?.resolve();await tick();app.unmount();scope.dispose();controller.abort();host.remove();window.HTMLElement.prototype.getClientRects=original}
 }
}

for(const selected of ['a','b'])test('successful real App scope deletion '+(selected==='a'?'selected':'nonselected')+' restores only the replacement fallback after slow reload and canonical navigation',async()=>{
 const view=await scenario({selected})
 try{
  const oldFallback=view.fallback(),trigger=await view.open(),reload=view.holdReload(),navigation=view.holdNavigation()
  await view.confirm();await wait(()=>!oldFallback.isConnected,'reload did not replace old fallback');assert(!trigger.isConnected)
  assert.equal(view.content.querySelector('main').getAttribute('aria-busy'),'true')
  reload.resolve();await wait(()=>view.navigations.length===1&&view.fallback(),'new authorized sidebar and canonical navigation did not settle')
  const current=view.fallback();assert(current!==oldFallback,'reload must replace fallback');assert(current.isConnected);assert(view.root.activeElement!==current,'do not restore before canonical navigation resolves')
  navigation.resolve();await wait(()=>view.root.activeElement===current,'focus did not return to the current replacement fallback')
  assert.equal(view.requests.filter(request=>request.method==='DELETE').length,1);assert.equal(view.navigations[0].target,'application-groups/g/scopes/b')
  assert.equal(view.content.querySelector('[data-test="scope-item"][aria-current="true"] code').textContent,'b')
 }finally{await view.close()}
})

test('cancel preserves the connected original trigger and does not delete, reload or jump to fallback',async()=>{
 const view=await scenario()
 try{const fallback=view.fallback(),trigger=await view.open(),count=view.requests.length;click(view.portal.querySelector('[data-test="delete-confirm"] button:not(.kel-button--danger)'));await wait(()=>view.root.activeElement===trigger,'cancel failed to restore original trigger');assert(trigger.isConnected);assert(view.fallback()===fallback,'existing fallback must not be replaced');assert.equal(view.requests.length,count);assert.equal(view.navigations.length,0)}finally{await view.close()}
})

test('412 keeps the original trigger/sidebar, rejects duplicate mutation, and cancel restores that trigger',async()=>{
 const view=await scenario({deleteStatus:412})
 try{const fallback=view.fallback(),trigger=await view.open();await view.confirm();await wait(()=>view.portal.querySelector('[data-test="delete-confirm"] [role="alert"]'),'412 alert missing');assert(trigger.isConnected);assert(view.fallback()===fallback,'existing fallback must not be replaced');assert(view.root.activeElement!==fallback,'must not move focus to fallback');await view.confirm();assert.equal(view.requests.filter(request=>request.method==='DELETE').length,1);assert.equal(view.navigations.length,0);click(view.portal.querySelector('[data-test="delete-confirm"] button:not(.kel-button--danger)'));await wait(()=>view.root.activeElement===trigger,'412 cancellation did not restore trigger')}finally{await view.close()}
})

test('successful delete does not steal focus from an original trigger that remains connected',async()=>{
 const view=await scenario()
 try{const persistent=view.content.querySelector('[data-test="persistent-shell-trigger"]');persistent.focus();await view.open('a',{keepFocus:true});const reload=view.holdReload();await view.confirm();await wait(()=>!view.button('a'),'old row did not unmount');reload.resolve();await wait(()=>view.navigations.length===1&&view.fallback()&&view.content.querySelector('main').getAttribute('aria-busy')==='false','reload not settled');await tick();assert(persistent.isConnected);assert(view.root.activeElement===persistent,'connected original trigger must retain focus');assert(view.root.activeElement!==view.fallback(),'must not focus fallback')}finally{await view.close()}
})

test('successful DELETE followed by failed authorized reload does not target a missing or stale fallback',async()=>{
 const view=await scenario({reloadStatus:503})
 try{const old=view.fallback();await view.open();await view.confirm();await wait(()=>view.content.querySelector('[data-test="request-error"]')&&view.content.querySelector('main').getAttribute('aria-busy')==='false','reload failure not exposed');assert(!old.isConnected);assert(view.fallback()===null,'unauthorized/failed reload must not expose sidebar fallback');assert.equal(view.navigations.length,0);assert.equal(view.requests.filter(request=>request.method==='DELETE').length,1)}finally{await view.close()}
})

test('canonical navigation failure after real deletion retains error focus rather than overriding it with fallback',async()=>{
 const view=await scenario({navigationReject:true})
 try{await view.open();await view.confirm();await wait(()=>view.content.querySelector('[data-test="request-error"]'),'navigation rejection missing');assert(view.root.activeElement===view.content.querySelector('[data-test="request-error"]'),'navigation error must keep focus');assert(view.root.activeElement!==view.fallback(),'must not focus fallback');assert.equal(view.requests.filter(request=>request.method==='DELETE').length,1)}finally{await view.close()}
})

test('read-only and in-flight delete locks survive; revoked reload never restores unauthorized sidebar',async()=>{
 const readonly=await scenario({manage:false})
 try{assert(!readonly.button('a'),'read-only scope must not expose a delete button');assert(!readonly.content.querySelector('[data-test="scope-add"]'),'read-only scope must not expose create');assert.equal(readonly.requests.filter(request=>request.method==='DELETE').length,0)}finally{await readonly.close()}
 const view=await scenario()
 try{const trigger=await view.open(),pending=view.holdDelete();await view.confirm();const buttons=[...view.portal.querySelector('[data-test="delete-confirm"]').querySelectorAll('.kel-drawer__footer button,.kel-dialog__footer button')];assert.equal(buttons.length,2);for(const button of buttons){assert(button.disabled);click(button)}assert.equal(view.requests.filter(request=>request.method==='DELETE').length,1);assert(trigger.disabled);view.revoke();pending.resolve();await wait(()=>view.content.querySelector('[data-test="request-error"]')&&!view.content.querySelector('[data-test="scope-sidebar"]'),'revoked reload did not deny');assert(view.fallback()===null,'unauthorized/failed reload must not expose sidebar fallback');assert.equal(view.navigations.length,0);assert.equal(view.requests.filter(request=>request.method==='DELETE').length,1)}finally{await view.close()}
})

test.after(()=>{hooks.deregister();window.happyDOM.abort()})
