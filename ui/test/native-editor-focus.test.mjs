import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync,readdirSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {registerHooks} from 'node:module'
import {Window} from 'happy-dom'
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc'

// Real editor, published native components and SDK; no HTTP or services.
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
const {default:KsDrawer}=await import('@kestra-io/design-system/components/Feedback/KsDrawer')
const {default:KsDialog}=await import('@kestra-io/design-system/components/Feedback/KsDialog')
const {KsButton}=await import('@kestra-io/design-system')
const {createShadowOverlayScope,createShadowRenderer,shadowOverlayComponents}=await import('../../.integration/ui-sdk/shadow-overlays.mjs')
const messages=Object.assign({},...readdirSync(new URL('../src/translations/',import.meta.url)).filter(name=>name.endsWith('.json')).map(name=>JSON.parse(readFileSync(new URL('../src/translations/'+name,import.meta.url),'utf8'))))
const tick=async()=>{await Vue.nextTick();await new Promise(resolve=>setTimeout(resolve,0));await Vue.nextTick()}
const {default:EditorDrawer}=await import('../src/EditorDrawer.vue')
const {createEditorState,openEditor,requestEditorClose}=await import('../src/editor-state.js')
const wait=async(predicate,message)=>{for(let i=0;i<150;i++){if(predicate())return;await tick()}assert(predicate(),message)}

// These are real Vue/Element Plus/SDK lifecycle tests, not native browser focus proof.
// HappyDOM has no layout or native Enter activation. Geometry is modeled solely
// for the published SDK's availability checks; the separate Playwright evidence
// uses real Chromium keyboard/mouse and the actual built production entry.
function scenario(){
 const host=document.createElement('div');document.body.append(host)
 const root=host.attachShadow({mode:'open'}),content=document.createElement('div'),portal=document.createElement('div');root.append(content,portal)
 const original=window.HTMLElement.prototype.getClientRects
 window.HTMLElement.prototype.getClientRects=function(){return [{width:100,height:30}]}
 const controller=new AbortController(),state=Vue.reactive(createEditorState()),busy=Vue.ref(false)
 const releases=[],closedListeners=[];let closed=0,releaseUsers
 portal.addEventListener('focus-trap.focus-after-released',event=>queueMicrotask(()=>releases.push(event.defaultPrevented)),true)
 const scope=createShadowOverlayScope({container:host,content,portal,signal:controller.signal,fallback:()=>content.querySelector('[data-focus-fallback]')})
 const adapted=shadowOverlayComponents(Vue,{KsDrawer,KsDialog},scope)
 // Observe/forward only the documented public closed listener. No component
 // setup state, vnode internals, SDK implementation, or native controls are replaced.
 const ObservedDrawer=Vue.defineComponent({inheritAttrs:false,setup(_props,{attrs,slots}){return()=>{
  if(attrs.onClosed&&!closedListeners.includes(attrs.onClosed))closedListeners.push(attrs.onClosed)
  return Vue.h(adapted.KsDrawer,attrs,slots)
 }}})
 const renderer=createShadowRenderer({...Vue,nodeOps,patchProp},scope,{idInjectionKey:ID_INJECTION_KEY,namespace:'kel'})
 const app=renderer.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>[
  Vue.h('main',{'data-focus-fallback':'',tabindex:-1},[Vue.h('button',{'data-test':'subject-add',disabled:busy.value,onClick:async()=>{
   busy.value=true;await new Promise(resolve=>releaseUsers=resolve);busy.value=false;openEditor(state,'subject',{name:'draft'})
  }},'Add subject')]),
  Vue.h(EditorDrawer,{state,title:'Edit',onClosed:()=>closed++},{default:()=>Vue.h('input')})
 ])})
 app.provide('rbac-portals',portal);app.component('KsButton',KsButton);app.component('KsDrawer',ObservedDrawer);app.component('KsDialog',adapted.KsDialog)
 app.use(createI18n({legacy:false,locale:'en',messages}));app.mount(content)
 return {root,state,closedListeners,releases,scope,closed:()=>closed,releaseUsers:()=>releaseUsers(),
  async open(){const trigger=content.querySelector('[data-test="subject-add"]');trigger.focus();trigger.click();await Vue.nextTick();assert(trigger.disabled);releaseUsers();await wait(()=>root.querySelector('.kel-drawer__close-btn'),'drawer opens after deferred user load');assert(!trigger.disabled);return trigger},
  close(){app.unmount();scope.dispose();host.remove();window.HTMLElement.prototype.getClientRects=original}}
}

test('native focus lifecycle: closing retains the cycle kind marker and registration until native release, then public closed cleans up',async()=>{
 const view=scenario()
 try{
  await view.open();await tick()
  const drawer=view.root.querySelector('[data-test="drawer-subject"]');assert(drawer)
  assert.equal(requestEditorClose(view.state),true);await Vue.nextTick()
  assert.equal(view.state.kind,'');assert.equal(view.root.querySelector('[data-test="drawer-subject"]'),drawer,'closing marker must not become drawer-')
  assert.equal(view.closed(),0,'closed is not business-state reset')
  await wait(()=>view.closed()===1,'native public closed completes')
  assert.equal(view.root.querySelector('[data-test="drawer-subject"]'),null)
  assert.equal(view.scope.inspect().modals.length,0)
  assert(view.releases.length>0);assert(view.releases.every(Boolean),'SDK registration prevents default native focus release before disposal')
 }finally{view.close()}
})

test('native focus lifecycle: stale public closed cannot clean a quickly reopened cycle or retain native maximize',async()=>{
 const view=scenario()
 try{
  await view.open();await tick()
  view.root.querySelector('.kel-drawer__header .is-link').click();await Vue.nextTick()
  assert(view.root.querySelector('.kel-drawer').classList.contains('full-screen'))
  const oldClosed=view.closedListeners.at(-1)
  requestEditorClose(view.state);await Vue.nextTick()
  assert(view.root.querySelector('[data-test="drawer-subject"]'))
  openEditor(view.state,'group',{name:'new'});await Vue.nextTick()
  const current=view.root.querySelector('[data-test="drawer-group"]');assert(current)
  assert(!current.classList.contains('full-screen'),'new cycle remounts the public native component')
  await oldClosed();await tick()
  assert.equal(view.root.querySelector('[data-test="drawer-group"]'),current)
  assert.equal(view.state.kind,'group');assert.equal(view.closed(),0)
  requestEditorClose(view.state);await wait(()=>view.closed()===1,'new cycle alone completes closed')
  assert.equal(view.root.querySelector('.kel-drawer'),null)
 }finally{view.close()}
})

test('native focus lifecycle: async-disabled subject trigger is restored after Enter-style close and native release',async()=>{
 const view=scenario()
 try{
  const trigger=await view.open();await tick()
  const expand=view.root.querySelector('.kel-drawer__header .is-link');expand.focus()
  expand.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));expand.click();await tick()
  const close=view.root.querySelector('.kel-drawer__close-btn');close.focus()
  // HappyDOM does not synthesize click from Enter: explicitly model activation.
  // The same sequence is exercised with trusted input in the Chromium harness.
  close.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));close.click()
  await wait(()=>view.closed()===1,'close and late native trap release complete')
  assert.equal(trigger.isConnected,true);assert.equal(trigger.disabled,false)
  assert.equal(view.root.activeElement,trigger)
  assert(view.releases.length>0);assert(view.releases.every(Boolean))
  await tick();assert.equal(view.root.activeElement,trigger,'focus remains after release/unmount')
 }finally{view.close()}
})
test.after(()=>{hooks.deregister();window.happyDOM.abort()})
