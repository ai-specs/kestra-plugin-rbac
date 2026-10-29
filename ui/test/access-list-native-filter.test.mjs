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
async function mountList(kind) {
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]})
  await router.push('/'+kind)
  const items=kind==='permissions'?[{key:'alpha',name:'Read team',scopeKeys:['a']},{key:'beta',name:'Read other',scopeKeys:['b']},{key:'gamma',name:'Write team',scopeKeys:['a']}]:[{subject:'alice',scopeKeys:['a']},{subject:'bob',scopeKeys:['b']},{subject:'carol',scopeKeys:['a']}]
  const fields=kind==='permissions'?['key','name','scopeKeys']:['subject','scopeKeys']
  const container=document.createElement('div');document.body.append(container)
  const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h(AccessList,{kind,items,columns:fields.map(key=>({key,label:key})),scopes:[{key:'a',name:'A'},{key:'b',name:'B'}]}))})
  for(const [name,component] of Object.entries(DS))if(name.startsWith('Ks')&&component&&typeof component==='object')app.component(name,component)
  const i18n=createI18n({legacy:false,locale:'en',messages});await DS.registerDesignSystemI18n(i18n)
  app.provide('rbac-portals',document.body) // virtual test portal; production injects its ShadowRoot-owned portal
  app.use(i18n);app.use(router);app.mount(container);await tick()
  return {container,router,items,i18n,close(){app.unmount();container.remove()}}
}
async function openAdvanced(view) {
  click(view.container.querySelector('.customize-button'));await tick()
  click([...document.querySelectorAll('.advanced-add')].find(shown));await tick()
  const dialog=document.querySelector('.adv-builder');assert(dialog);return dialog
}
async function select(control,label) {
  click(control.querySelector('.kel-select__wrapper'));await tick()
  const matches=[...document.querySelectorAll('[role="option"]')].filter(node=>shown(node)&&node.textContent.trim()===label)
  assert.equal(matches.length,1,'exact native option '+label+' absent/ambiguous');click(matches[0]);await tick()
}
const rows=dialog=>[...dialog.querySelectorAll('.adv-row')]
const ids=(view,kind)=>[...view.container.querySelectorAll('.kel-table__body-wrapper tbody tr')].map(row=>row.querySelector('td')?.textContent.trim()).filter(Boolean)

for(const kind of ['users','permissions','subjects'])test(kind+': native Add condition creates rows and serializes supported AND filters with history recovery',async()=>{
  const view=await mountList(kind)
  try {
    const dialog=await openAdvanced(view),add=dialog.querySelector('.adv-footer .adv-add')
    const groupButton=dialog.querySelector('.adv-footer .adv-add:nth-of-type(2)');assert.equal(groupButton.disabled,true);assert.equal(groupButton.getAttribute('aria-disabled'),'true');const note=dialog.querySelector('[data-and-filter-note]');assert(note&&note.textContent.includes('AND'));assert.equal(groupButton.getAttribute('aria-describedby'),note.id);
    for(const type of ['click','mousedown','pointerdown'])groupButton.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true}));
    for(const key of ['Enter',' '])groupButton.dispatchEvent(new KeyboardEvent('keydown',{key,bubbles:true,cancelable:true}));
    await tick();assert.equal(rows(dialog).length,0);click(add);await tick();assert.equal(rows(dialog).length,1,'enabled Add condition must create a real ConditionRow')
    const first=rows(dialog)[0]
    await select(first.querySelector('.cond-value-select'),kind==='permissions'?'alpha':'alice')
    const firstPath=view.router.currentRoute.value.fullPath
    assert(nativeChips(DS.decodeSearchParams(view.router.currentRoute.value.query),kind).length===1)
    assert.deepEqual(ids(view,kind),[kind==='permissions'?'alpha':'alice'])
    if(kind==='users') {
      // Native filters keep one condition per field/comparator. Conjoin with the existing deep link.
      await view.router.push({query:{...view.router.currentRoute.value.query,filterSubject:'alice'}});await tick()
      await select(rows(dialog)[0].querySelector('.cond-value-select'),'bob')
    } else {
      click(add);await tick();assert.equal(rows(dialog).length,2)
      const second=rows(dialog)[1]
      if(kind==='permissions') {
        const input=second.querySelector('.cond-value-input input');assert(input);input.value='Write';input.dispatchEvent(new Event('input',{bubbles:true}));await tick()
      } else await select(second.querySelector('.cond-value-select'),'b')
    }
    const combined=()=>[...readListQuery(view.router.currentRoute.value.query,kind).chips,...nativeChips(DS.decodeSearchParams(view.router.currentRoute.value.query),kind)]
    const chips=combined()
    assert.equal(chips.length,2);assert(view.items.some(item=>matchesChips(item,[chips[0]])));assert(view.items.some(item=>matchesChips(item,[chips[1]])))
    assert(!view.items.some(item=>matchesChips(item,chips)),'two separately matching predicates must combine as AND')
    assert.deepEqual(ids(view,kind),[]);assert(!view.container.querySelector('[data-test="request-error"]'))
    const encoded=view.router.currentRoute.value.fullPath;assert(encoded!==firstPath)
    await view.router.push('/'+kind);await tick();assert.equal(ids(view,kind).length,3)
    await new Promise(resolve=>{const remove=view.router.afterEach(()=>{remove();resolve()});view.router.back()});await tick()
    assert.equal(view.router.currentRoute.value.fullPath,encoded);assert.deepEqual(ids(view,kind),[])
    assert.equal(combined().length,2)
  }finally{await window.happyDOM.waitUntilComplete();view.close()}
})
test('native filter OR and unsupported comparator remain rejected instead of widening AND semantics',()=>{
  for(const kind of ['users','permissions','subjects']) {
    assert.throws(()=>nativeChips([{field:kind==='permissions'?'key':'subject',operation:'EQUALS',value:'a',topLogical:'OR'}],kind))
    assert.throws(()=>nativeChips([{field:kind==='permissions'?'key':'subject',operation:'NOT_EQUALS',value:'a'}],kind))
  }
})
test('owned AND adapter: two registered areas never change another layer; malformed DOM fails closed and disposal removes events',async()=>{
  const {ownAndFilter}=await import('../src/and-filter-owner.js')
  const host=document.createElement('section'),portal=document.createElement('section');document.body.append(host,portal)
  const areas=[0,1].map(()=>{const area=document.createElement('div');area.innerHTML='<button class="customize-button">Open</button>';host.append(area);return area})
  const failures=[0,0],owners=areas.map((area,index)=>ownAndFilter({area,portal,description:()=>"AND only "+index,descriptionId:'and-'+index,fail:()=>failures[index]++}))
  const layer=()=>{const node=document.createElement('div');node.className='adv-builder';node.innerHTML='<div class="adv-body"><button class="adv-conj toggle">OR</button></div><div class="adv-footer"><button class="adv-add">Condition</button><button class="adv-add">Group</button></div>';return node}
  try {
    areas[0].querySelector('button').click();const first=layer();portal.append(first);await tick()
    assert(first.querySelector('.adv-conj').disabled);assert(first.querySelector('#and-0'));assert(!first.querySelector('.adv-add').disabled)
    areas[1].querySelector('button').click();const second=layer();portal.append(second);await tick()
    assert(second.querySelector('#and-1'));assert(!first.querySelector('#and-1'))
    const arbitrary=layer();portal.append(arbitrary);await tick();assert(!arbitrary.querySelector('.adv-conj').disabled,'unassociated layer must remain untouched')
    arbitrary.remove();areas[1].querySelector('button').click();const broken=document.createElement('div');broken.className='adv-builder';portal.append(broken);await tick()
    assert.equal(broken.inert,true);assert(failures[1]>0);assert.equal(failures[0],0)
    owners[0].dispose();const event=new MouseEvent('click',{bubbles:true,cancelable:true});first.querySelector('.adv-conj').dispatchEvent(event);assert.equal(event.defaultPrevented,false,'disposed adapter capture listener remains')
  }finally{owners.forEach(owner=>owner.dispose());host.remove();portal.remove()}
})

test('group cards: native AND-only UI preserves id/displayName/active comparisons, OR URL shows clearable error',async()=>{
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});await router.push('/groups')
  const items=[{id:'a',displayName:'Alpha',active:true,counts:{}},{id:'b',displayName:'Beta',active:false,counts:{}}]
  const container=document.createElement('div');document.body.append(container)
  const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h(ApplicationGroupCards,{items,storageKey:'owned-test'}))})
  for(const [name,component]of Object.entries(DS))if(name.startsWith('Ks')&&component&&typeof component==='object')app.component(name,component)
  const i18n=createI18n({legacy:false,locale:'en',messages});await DS.registerDesignSystemI18n(i18n);app.use(i18n);app.use(router);app.provide('rbac-portals',document.body);app.mount(container)
  try {
    await tick();const dialog=await openAdvanced({container});const group=dialog.querySelector('.adv-footer .adv-add:nth-of-type(2)');assert(group.disabled);assert(group.getAttribute('aria-describedby'))
    click(dialog.querySelector('.adv-footer .adv-add'));await tick();assert.equal(rows(dialog).length,1)
    const input=dialog.querySelector('.cond-value-input input');input.value='a';input.dispatchEvent(new Event('input',{bubbles:true}));await tick()
    assert.deepEqual([...container.querySelectorAll('[data-group-id]')].map(node=>node.dataset.groupId),['a'])
    group.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true})) // capture guard also rejects synthetic activation
    assert.equal(rows(dialog).length,1)
    const {matchesQuery}=await import('../src/list-filters.js')
    assert(matchesQuery(items[0],[{field:'id',operation:'EQUALS',value:'a'},{field:'displayName',operation:'CONTAINS',value:'Al'},{field:'active',operation:'EQUALS',value:'true'}],(item,key)=>String(item[key])))
    await router.push({path:'/groups',query:{'filters[or][0][id][EQUALS]':'a','filters[or][1][displayName][EQUALS]':'Beta'}});await tick();assert(container.querySelector('[data-test="request-error"]'));assert.equal(container.querySelector('[data-test="application-group-cards"]'),null);assert.equal(container.querySelector('.kel-pagination'),null);assert.equal(items.length,2)
    click(container.querySelector('[data-test="request-error"] button'));await tick();assert.equal(container.querySelector('[data-test="request-error"]'),null);assert.equal(container.querySelectorAll('[data-group-id]').length,2)
    await router.push({path:'/groups',query:{'filters[id][EQUALS]':'missing'}});await tick();assert(!container.querySelector('[data-test="request-error"]'));assert(container.querySelector('[data-test="application-group-cards"]'));assert(container.querySelector('.kel-pagination'));assert.equal(container.querySelectorAll('[data-group-id]').length,0)
  }finally{await window.happyDOM.waitUntilComplete();app.unmount();container.remove()}
})

test('AND owner cleanup releases detached layer and rejected claims, and A pending then B open only binds B',async()=>{
 const {ownAndFilter}=await import('../src/and-filter-owner.js')
 const host=document.createElement('div'),portal=document.createElement('div');document.body.append(host,portal)
 const area=()=>{const node=document.createElement('div');node.innerHTML='<button class="customize-button">Open</button>';host.append(node);return node}
 const a=area(),b=area();let failed=0
 const first=ownAndFilter({area:a,portal,description:()=>"AND A",descriptionId:'note-a',fail:()=>failed++})
 const second=ownAndFilter({area:b,portal,description:()=>"AND B",descriptionId:'note-b',fail:()=>failed++})
 const layer=()=>{const node=document.createElement('div');node.className='adv-builder';node.innerHTML='<div class="adv-body"></div><div class="adv-footer"><button class="adv-add">Condition</button><button class="adv-add">Group</button></div>';return node}
 try {
  a.querySelector('button').click();b.querySelector('button').click();const owned=layer();portal.append(owned);await tick();assert(owned.querySelector('#note-b'));assert(!owned.querySelector('#note-a'))
  owned.remove();await tick();assert.equal(owned.querySelector('[data-and-filter-note]')===null,true,'detached layer retains owner note');assert.equal(owned.querySelector('button:last-child').disabled,false,'detached layer retains disabled mutation')
  a.querySelector('button').click();const broken=layer();broken.querySelector('.adv-body').remove();portal.append(broken);await tick();assert(broken.inert)
  first.dispose();assert.equal(broken.inert,false,'rejected layer inert was not restored on owner disposal');assert.equal(broken.hasAttribute('data-and-filter-invalid'),false)
  assert(failed>0)
 }finally{first.dispose();second.dispose();host.remove();portal.remove()}
})

test('AND owner accepts keyboard-style click without pointer activation on customize and rules-pill',async()=>{
 const {ownAndFilter}=await import('../src/and-filter-owner.js')
 const area=document.createElement('section'),portal=document.createElement('section');area.innerHTML='<button class="customize-button">Add</button><button class="rules-pill">Rules</button>';document.body.append(area,portal)
 const owner=ownAndFilter({area,portal,description:()=>"AND only",descriptionId:'keyboard-and',fail:()=>assert.fail('valid owned structure rejected')})
 try {
  for(const selector of ['.customize-button','.rules-pill']) {
   const trigger=area.querySelector(selector);trigger.focus();assert.equal(document.activeElement,trigger)
   // Native button keyboard activation delivers a click with detail=0. Happy DOM does not synthesize that click from keydown.
   trigger.dispatchEvent(new MouseEvent('click',{bubbles:true,composed:true,cancelable:true,detail:0}))
   const layer=document.createElement('div');layer.className='adv-builder';layer.innerHTML='<div class="adv-body"></div><div class="adv-footer"><button class="adv-add">Condition</button><button class="adv-add">Group</button></div>';portal.append(layer);await tick()
   assert(layer.querySelector('button:last-of-type').disabled);assert(layer.querySelector('#keyboard-and'));layer.remove();await tick()
  }
 }finally{owner.dispose();area.remove();portal.remove()}
})

test('AND owner restores native attribute-only patches without mutation-observer loops',async()=>{
 const {ownAndFilter}=await import('../src/and-filter-owner.js');const area=document.createElement('div'),portal=document.createElement('div');area.innerHTML='<button class="customize-button">Open</button>';document.body.append(area,portal)
 const owner=ownAndFilter({area,portal,description:()=>"AND only",descriptionId:'patch-and',fail:()=>assert.fail('owned native structure rejected')})
 try {
  area.querySelector('button').click();const layer=document.createElement('div');layer.className='adv-builder';layer.innerHTML='<div class="adv-body"></div><div class="adv-footer"><button class="adv-add">Condition</button><button class="adv-add">Group</button></div>';portal.append(layer);await tick()
  const button=layer.querySelector('button:last-of-type');assert(button.disabled)
  button.disabled=false;button.setAttribute('aria-disabled','false');button.removeAttribute('aria-describedby');await tick()
  assert.equal(button.disabled,true);assert.equal(button.getAttribute('aria-disabled'),'true');assert.equal(button.getAttribute('aria-describedby'),'patch-and')
  let mutations=0;const observer=new MutationObserver(records=>{mutations+=records.length});observer.observe(layer,{subtree:true,attributes:true});await tick();await tick();observer.disconnect();assert.equal(mutations,0,'unchanged attributes create an observer loop')
 }finally{owner.dispose();area.remove();portal.remove()}
})

test('AND note is a sibling after native footer, including long German/French copy; aria stays in the same root',async()=>{
 const {ownAndFilter}=await import('../src/and-filter-owner.js');const area=document.createElement('div'),portal=document.createElement('div');area.innerHTML='<button class="customize-button">Open</button>';document.body.append(area,portal)
 let message='Alle Bedingungen müssen gleichzeitig erfüllt sein. Bedingungsgruppen und ODER sind in dieser Liste nicht verfügbar.'
 const owner=ownAndFilter({area,portal,description:()=>message,descriptionId:'long-and',fail:()=>assert.fail('owned structure rejected')})
 try {
  area.querySelector('button').click();const layer=document.createElement('div');layer.className='adv-builder';layer.innerHTML='<div class="adv-body"></div><div class="adv-footer"><button class="adv-add">Condition</button><button class="adv-add">Group</button><button class="adv-clear">Reset</button></div>';portal.append(layer);await tick()
  const footer=layer.querySelector('.adv-footer'),note=layer.querySelector('#long-and');assert.equal(footer.children.length,3);assert.equal(footer.nextElementSibling,note);assert.equal(note.parentNode,layer);assert.equal(note.getRootNode(),footer.getRootNode());assert.equal(note.style.overflowWrap,'anywhere');assert.equal(note.style.whiteSpace,'normal')
  message='Toutes les conditions doivent être satisfaites simultanément. Les groupes de conditions et OU ne sont pas disponibles dans cette liste.';owner.refresh();await tick();assert.equal(note.textContent,message);assert.equal(footer.children.length,3)
  assert.equal(footer.querySelectorAll('.adv-add')[1].getAttribute('aria-describedby'),note.id)
 }finally{owner.dispose();area.remove();portal.remove()}
})

test('owned mobile shown=false hides only Saved/Save, survives attribute patches and restores on detach/dispose',async()=>{
 const {ownAndFilter}=await import('../src/and-filter-owner.js');const area=document.createElement('section'),portal=document.createElement('section');area.innerHTML='<button class="mobile-toggle">Filters</button>';document.body.append(area,portal)
 let allText='All Filters',savedText='My Filters saved'
 const owner=ownAndFilter({area,portal,description:()=>"AND only",descriptionId:'mobile-note',savedFiltersShown:false,allLabel:()=>allText,savedLabel:()=>savedText,fail:()=>assert.fail('valid mobile structure rejected')})
 try {
  for(const cycle of [0,1]) {
   area.querySelector('button').click();const sheet=document.createElement('div');sheet.className='mobile-sheet';sheet.innerHTML='<div class="sheet-tabs"><span class="kel-segmented__item-selected"></span><label class="kel-segmented__item"><input type="radio" checked><span class="kel-segmented__item-label">All Filters</span></label><label class="kel-segmented__item"><input type="radio"><span class="kel-segmented__item-label">My Filters saved</span></label></div><div class="field-list"><button>Kind</button></div><div class="sheet-footer"><button class="sheet-clear">Clear all</button><div class="sheet-footer-actions"><button class="no-bg-border">Save</button><button class="sheet-apply">Apply filters</button></div></div>';portal.append(sheet);await tick()
   const labels=sheet.querySelectorAll('label'),save=sheet.querySelector('.no-bg-border'),apply=sheet.querySelector('.sheet-apply');assert(labels[1].hidden&&labels[1].inert&&labels[1].querySelector('input').disabled);assert(save.hidden&&save.disabled);assert(!labels[0].hidden);assert(!apply.hidden&&!apply.disabled);assert(!sheet.querySelector('.sheet-clear').hidden);assert.equal(labels[0].style.flex,'1 1 100%');assert.equal(sheet.querySelector('.kel-segmented__item-selected').style.width,'100%')
   for(const node of [labels[1],save]) {node.hidden=false;node.inert=false;node.removeAttribute('style');node.disabled=false};await tick();assert(save.hidden&&save.disabled);assert(labels[1].hidden)
   let activated=0;save.addEventListener('click',()=>activated++);save.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));save.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));assert.equal(activated,0)
   for(const [all,saved]of [['Tous les filtres','Mes filtres enregistrés'],['Alle Filter','Meine gespeicherten Filter']]) {
    allText=all;savedText=saved;labels[0].querySelector('.kel-segmented__item-label').textContent=all;labels[1].querySelector('.kel-segmented__item-label').textContent=saved;await Vue.nextTick();owner.refresh();await tick();assert(labels[1].hidden&&save.hidden);assert(!apply.disabled)
   }
   allText='All Filters';savedText='My Filters saved'
   apply.focus();assert.equal(document.activeElement,apply)
   sheet.remove();await tick();assert(!save.hidden&&!save.disabled);assert(!labels[1].hidden);assert.equal(labels[0].getAttribute('style'),null)
  }
 }finally{owner.dispose();area.remove();portal.remove()}
})

test('real mobile filter false configuration preserves All/field/Clear/Apply, locale change, and malformed sheet clear remount',async()=>{
 window.happyDOM.setWindowSize({width:390,height:900})
 const view=await mountList('users')
 const originalConfirm=DS.KsMessageBox.confirm;let confirmations=0;DS.KsMessageBox.confirm=()=>{confirmations++;throw new Error('unexpected MessageBox')}
 const savedState=()=>Object.fromEntries(Object.keys(localStorage).filter(key=>key.startsWith('saved_filters_')).map(key=>[key,localStorage.getItem(key)]))
 const beforeSaved=savedState()
 try {
  await tick();const toggle=view.container.querySelector('.mobile-toggle');assert(toggle,'native responsive mobile entry missing');click(toggle);await tick()
  let sheet=[...document.querySelectorAll('.mobile-sheet')].find(shown);assert(sheet)
  const tabs=[...sheet.querySelectorAll('.sheet-tabs label')];assert.equal(tabs.length,2);assert(!tabs[0].hidden);assert(tabs[1].hidden);assert(sheet.querySelector('.sheet-apply'));assert(sheet.querySelector('.sheet-footer-actions button:not(.sheet-apply)').hidden)
  click(sheet.querySelector('.field-row-header'));await tick();assert(sheet.querySelector('.field-row-body'))
  await select(sheet.querySelector('.field-row-body .kel-select'),'alice')
  click(sheet.querySelector('.sheet-apply'));await tick();assert.deepEqual(ids(view,'users'),['alice']);assert(Object.keys(view.router.currentRoute.value.query).some(key=>key.startsWith('filters[')))
  click(view.container.querySelector('.mobile-toggle'));await tick();sheet=[...document.querySelectorAll('.mobile-sheet')].find(shown)
  for(const locale of ['fr','de']) {
    view.i18n.global.locale.value=locale;await tick();await tick()
    assert(!view.container.querySelector('[data-test="request-error"]'),'locale post-flush caused false association failure')
    const labels=[...sheet.querySelectorAll('.sheet-tabs label')];assert(!labels[0].hidden&&labels[1].hidden);assert(!sheet.querySelector('.sheet-apply').disabled)
  }
  click(sheet.querySelector('.sheet-clear'));await tick();assert.equal(ids(view,'users').length,3);assert(!Object.keys(view.router.currentRoute.value.query).some(key=>key.startsWith('filters[')))

  // Corrupt only this owned instance; public Vue unmount must remove its live native layer.
  sheet.querySelector('.sheet-footer-actions .sheet-apply').remove();await tick();await tick()
  assert.equal(view.container.querySelector('.mobile-toggle'),null,'failed native filter remains mounted')
  assert.equal(document.querySelector('.mobile-sheet'),null,'unmounted native sheet remains in portal')
  const error=view.container.querySelector('[data-test="request-error"]');assert(error);click(error.querySelector('button'));await tick();assert(view.container.querySelector('.mobile-toggle'),'Clear did not remount valid native filter');assert.equal(confirmations,0);assert.deepEqual(savedState(),beforeSaved,'unavailable saved action wrote filter records')
 }finally{DS.KsMessageBox.confirm=originalConfirm;view.close();window.happyDOM.setWindowSize({width:1024,height:768})}
})

test.after(async()=>{await window.happyDOM.waitUntilComplete();hooks.deregister()})
