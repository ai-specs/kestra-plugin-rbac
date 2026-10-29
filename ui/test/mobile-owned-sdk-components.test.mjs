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

const {ID_INJECTION_KEY}=await import('element-plus')
const {nodeOps,patchProp}=await import('@vue/runtime-dom')
const {createShadowOverlayScope,createShadowRenderer,shadowOverlayComponents}=await import('../../.integration/ui-sdk/shadow-overlays.mjs')
for(const mode of ['normal','other-modal','ambiguous-scrim'])test('published SDK mobile owned cleanup, two instances and repeated recovery: '+mode,async()=>{
 const otherModal=mode==='other-modal'
 window.happyDOM.setWindowSize({width:390,height:900})
 const host=document.createElement('div');document.body.append(host);const root=host.attachShadow({mode:'open'}),content=document.createElement('div'),portal=document.createElement('div');root.append(content,portal)
 const scope=createShadowOverlayScope({container:host,content,portal,fallback:()=>content.querySelector('[data-focus-fallback]')})
 const renderer=createShadowRenderer({...Vue,nodeOps,patchProp},scope,{idInjectionKey:ID_INJECTION_KEY,namespace:"kel"})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});await router.push('/users')
 const app=renderer.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h('div',{},['first','second'].map(id=>Vue.h('section',{'data-owner':id},Vue.h(AccessList,{kind:'users',items:[{subject:id==='first'?'alice':'bob'}],columns:[{key:'subject',label:'subject'}],scopes:[]})))))})
 const i18n=createI18n({legacy:false,locale:'en',messages});await DS.registerDesignSystemI18n(i18n)
 for(const [name,component] of Object.entries(DS))if(name.startsWith('Ks')&&!['KsSelect','KsPopover','KsTooltip','KsDropdown'].includes(name)&&component&&typeof component==='object')app.component(name,component)
 const adapted=shadowOverlayComponents(Vue,{KsSelect:DS.KsSelect,KsPopover:DS.KsPopover,KsTooltip:DS.KsTooltip,KsDropdown:DS.KsDropdown},scope)
 for(const [name,component]of Object.entries(adapted))app.component(name,component)
 app.use(router);app.use(i18n);app.provide('rbac-portals',portal);app.mount(content)
 try{
  await tick();click(content.querySelector('.mobile-toggle'));await tick();await tick()
  const sheet=portal.querySelector('.mobile-sheet');assert(sheet,'published renderer must migrate native mobile sheet into portal');assert(content.inert);assert.equal(scope.inspect().modals.length,1)
  let other,releaseOther
  if(otherModal){other=document.createElement('div');other.innerHTML='<button>Other modal</button>';portal.append(other);releaseOther=scope.activate(other,()=>{});assert.equal(scope.inspect().modals.length,2)}
  let decoy,decoyClicks=0
  if(mode==='ambiguous-scrim'){decoy=document.createElement('div');decoy.className='mobile-scrim';decoy.onclick=()=>decoyClicks++;portal.append(decoy)}
  sheet.querySelector('.sheet-apply').remove();await tick();await tick();await new Promise(r=>setTimeout(r,100));await tick()
  assert(!portal.querySelector('.mobile-sheet'),'malformed migrated sheet survived filter unmount')
  assert.equal(portal.querySelectorAll('.mobile-scrim').length,decoy?1:0,'owned native scrim survived cleanup');if(decoy){assert(decoy.isConnected);assert.equal(decoyClicks,0);decoy.remove()}assert.equal(scope.inspect().modals.length,otherModal?1:0);if(otherModal){assert(other.isConnected,'unrelated layer was removed');assert(content.inert,'unrelated modal lost inert protection');releaseOther();other.remove()}assert(!content.inert,'error Clear remains blocked by modal inert')
  const error=content.querySelector('[data-test="request-error"]');assert(error);const clear=error.querySelector('button');clear.focus();assert(root.activeElement===clear);click(clear);await tick();click(content.querySelector('.mobile-toggle'));await tick();assert(portal.querySelector('.mobile-sheet .sheet-apply'),'Clear failed to remount native filter')
  click(portal.querySelector('.mobile-sheet .sheet-apply'));await tick();await tick()
  for(let reopening=0;reopening<2;reopening++){
    click(content.querySelector('[data-owner="second"] .mobile-toggle'));await tick();assert(portal.querySelector('.mobile-sheet .sheet-apply'),'other instance corrupted by first cleanup')
    click(portal.querySelector('.mobile-sheet .sheet-apply'));await tick();await tick();assert.equal(scope.inspect().modals.length,0);assert(!content.inert)
  }
 }finally{app.unmount();scope.dispose();host.remove();window.happyDOM.setWindowSize({width:1024,height:768})}
})
const {ownAndFilter}=await import('../src/and-filter-owner.js')
for(const mode of ['once','dispose','reset-new-generation'])test('owned rejection delayed notification isolation: '+mode,async()=>{
 const host=document.createElement('div');document.body.append(host);const root=host.attachShadow({mode:'open'}),area=document.createElement('div'),portal=document.createElement('div');root.append(area,portal)
 area.innerHTML='<button class="customize-button">Filters</button>';let failed=0,closed=0
 const owner=ownAndFilter({area,portal,description:()=> 'AND',descriptionId:'isolation',fail:()=>failed++})
 const overlay=document.createElement('div');overlay.className='adv-overlay';overlay.innerHTML='<div class="adv-builder"><div class="adv-body"></div><div class="adv-footer"><button class="adv-add">Condition</button><button class="adv-add">Group</button><button>Clear</button></div></div>'
 overlay.onclick=()=>closed++
 try{
  area.querySelector('button').click();portal.append(overlay);await tick();const layer=overlay.querySelector('.adv-builder');layer.querySelector('.adv-body').remove();await tick();assert.equal(closed,1);assert.equal(failed,0,'failure must wait until native layer disconnects')
  owner.refresh();assert.equal(closed,1,'rejected layer must not close twice')
  if(mode==='dispose')owner.dispose()
  if(mode==='reset-new-generation')owner.reset()
  overlay.remove();await tick();assert.equal(failed,mode==='once'?1:0)
  owner.refresh();await tick();assert.equal(failed,mode==='once'?1:0)
 }finally{owner.dispose();host.remove()}
})
for(const count of [0,2])test('owned malformed mobile with '+count+' candidate scrims fails closed without guessing a close target',async()=>{
 const host=document.createElement('div');document.body.append(host);const root=host.attachShadow({mode:'open'}),area=document.createElement('div'),portal=document.createElement('div');root.append(area,portal);area.innerHTML='<button class="mobile-toggle">Filters</button>'
 let failed=0,clicks=0
 const owner=ownAndFilter({area,portal,description:()=> 'AND',descriptionId:'unknown',savedLabel:()=> 'Saved',allLabel:()=> 'All',fail:()=>failed++})
 try{
  area.querySelector('button').click()
  for(let i=0;i<count;i++){const scrim=document.createElement('div');scrim.className='mobile-scrim';scrim.onclick=()=>clicks++;portal.append(scrim)}
  const sheet=document.createElement('div');sheet.className='mobile-sheet';portal.append(sheet);await tick()
  assert.equal(failed,1);assert.equal(clicks,0);assert(sheet.inert);assert(sheet.isConnected,'unsupported recovery must not fake removal')
  owner.refresh();assert.equal(failed,1);owner.dispose();assert(!sheet.inert);assert.equal(clicks,0)
 }finally{owner.dispose();host.remove()}
})
test.after(()=>{hooks.deregister();window.happyDOM.abort()})
