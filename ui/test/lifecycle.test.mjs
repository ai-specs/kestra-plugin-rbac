import test from 'node:test'
import assert from 'node:assert/strict'
import {registerHooks} from 'node:module'
import {createRenderer, defineComponent, h, Teleport, nextTick} from 'vue'

const hooks = registerHooks({
  load(url, context, nextLoad) {
    const result=nextLoad(url, context)
    if (url.endsWith('/src/lifecycle.js')) return {...result,source:String(result.source).replace("import.meta.glob('./translations/*.json', {eager: true, import: 'default'})",'{}')}
    return result
  },
  resolve(specifier, context, nextResolve) {
    if (context.parentURL?.endsWith('/src/lifecycle.js') && (specifier === '@kestra-io/design-system' || specifier.endsWith('.vue') || specifier.endsWith('/locales.mjs'))) {
      return {url:'data:text/javascript,' + encodeURIComponent(specifier === '@kestra-io/design-system'
        ? 'export const registerDesignSystemI18n=()=>{};export const KsButton={},KsInput={},KsSearch={},KsPopover={},KsTooltip={},KsIcon={},KsSelect={},KsOption={},KsDatePicker={},KsTag={},KsSegmented={};'
        : specifier.endsWith('.vue') ? 'export default {}' : 'export const resolveLocale=value=>value'),shortCircuit:true}
    }
    return nextResolve(specifier, context)
  }
})
const {createPortalSelect} = await import('../src/lifecycle.js')
hooks.deregister()

const node = (type, text = '') => ({type,text,children:[],parent:null,props:{}})
function renderer() {
  return createRenderer({
    createElement: type => node(type),createText: text => node('text',text),createComment: text => node('comment',text),
    setText: (target,text) => {target.text=text},setElementText: (target,text) => {target.text=text},
    parentNode: target => target.parent,nextSibling: target => target.parent?.children[target.parent.children.indexOf(target)+1] ?? null,
    patchProp: (target,key,old,value) => {target.props[key]=value},
    insert(target,parent,anchor=null) {
      if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target),1)
      const index=anchor?parent.children.indexOf(anchor):-1
      parent.children.splice(index<0?parent.children.length:index,0,target);target.parent=parent
    },
    remove(target) {if(target.parent)target.parent.children.splice(target.parent.children.indexOf(target),1);target.parent=null},
    querySelector() {throw new Error('select must receive its local portal element, never a global selector')}
  })
}

test('select dropdown stays in its own portal, forwards selection and releases options on unmount', async () => {
  const render=renderer(),first=node('first-shadow-root'),second=node('second-shadow-root'),global=node('document-body')
  const portalA=node('portal-a'),portalB=node('portal-b')
  const seen=[]
  const select=defineComponent({props:['appendTo','teleported','modelValue'],emits:['update:modelValue'],setup(props,{slots,emit}) {
    seen.push(props)
    return () => h(Teleport,{to:props.appendTo,disabled:!props.teleported},h('listbox',{onSelect:value=>emit('update:modelValue',value)},slots.default?.()))
  }})
  const selected=[]
  const appA=render.createApp({render:()=>h(createPortalSelect(select,portalA),{modelValue:'a',appendTo:global,teleported:false,'onUpdate:modelValue':value=>selected.push(value)},{default:()=>h('option',{value:'a'},'Project ID')})})
  const appB=render.createApp({render:()=>h(createPortalSelect(select,portalB),{modelValue:'b'},{default:()=>h('option',{value:'b'},'OAuth2 scope')})})
  appA.mount(first);appB.mount(second);await nextTick()
  assert.equal(seen[0].appendTo,portalA);assert.equal(seen[1].appendTo,portalB)
  assert(seen.every(props=>props.teleported===true))
  const listA=portalA.children.find(item=>item.type==='listbox'),listB=portalB.children.find(item=>item.type==='listbox')
  assert.equal(listA.children[0].text,'Project ID');assert.equal(listB.children[0].text,'OAuth2 scope')
  listA.props.onSelect('changed');assert.deepEqual(selected,['changed'])
  assert.equal(global.children.length,0)
  appA.unmount();assert.equal(portalA.children.length,0);assert(portalB.children.includes(listB))
  appB.unmount();assert.equal(portalB.children.length,0)
})
