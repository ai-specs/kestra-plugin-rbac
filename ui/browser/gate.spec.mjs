import {test,expect} from '@playwright/test'
import {cases} from './matrix.mjs'
const paths={group:'application-groups',user:'users',permission:'application-groups/team/applications/~Y2xpZW50',subject:'application-groups/team/scope-grants'}
const root=page=>page.locator('#plugin')
const by=(page,id)=>root(page).locator(`[data-test="${id}"]`)
const drawer=(page,kind)=>by(page,`drawer-${kind}`)
async function boot(page,row,{path='application-groups',state='normal'}={}){
  await page.setViewportSize({width:row.width,height:900})
  await page.emulateMedia({colorScheme:'light',reducedMotion:'reduce'})
  await page.goto('/?'+new URLSearchParams({theme:row.theme,locale:row.locale,path,state}))
  await page.waitForFunction(()=>window.fixture?.ready)
  if(state==='normal'||state==='empty')await expect(by(page,'access-control-root')).toHaveAttribute('aria-busy','false')
}
async function text(page,key){return page.evaluate(key=>window.fixture.messages[key],key)}
async function openDrawer(page,kind){
  const trigger=kind==='group'?root(page).locator('.management-toolbar button'):by(page,kind==='user'?'user-create':kind==='permission'?'permission-add':'subject-add')
  await trigger.click();await expect(drawer(page,kind)).toBeVisible();return trigger
}
async function dirty(page,kind){
  const d=drawer(page,kind)
  if(kind==='subject'){
    await d.locator('.kel-select__wrapper').first().click()
    await root(page).locator('[role=option]').filter({hasText:'bob'}).click()
  }else await d.locator('input').first().fill('fixture-draft')
}
async function fillValid(page,kind){
  const d=drawer(page,kind)
  if(kind==='group'){await d.locator('input').nth(0).fill('created-fixture');await d.locator('input').nth(1).fill('Created fixture')}
  if(kind==='user')await d.locator('input[name=username]').fill('created-fixture')
  if(kind==='permission'){await d.locator('[name=permission-key]').fill('new');await d.locator('[name=permission-name]').fill('New permission')}
  if(kind==='subject')await dirty(page,kind)
}
async function styleSnapshot(locator){return locator.evaluate(node=>{
  const properties=['backgroundColor','color','fontFamily','fontSize','fontWeight','paddingTop','paddingRight','paddingBottom','paddingLeft','borderRadius','marginTop','marginRight','marginBottom','marginLeft','lineHeight','columnGap','rowGap','alignItems','justifyContent','borderTopColor','boxShadow']
  const style=selector=>{const element=selector?node.querySelector(selector):node;if(!element)throw new Error('Missing style target '+selector);const css=getComputedStyle(element);return Object.fromEntries(properties.map(key=>[key,css[key]]))}
  return {panel:style(''),header:style('.kel-drawer__header'),body:style('.kel-drawer__body'),footer:style('.kel-drawer__footer'),
    icons:[...node.querySelectorAll('.kel-drawer__header svg')].map(svg=>({viewBox:svg.getAttribute('viewBox'),paths:[...svg.querySelectorAll('path')].map(path=>path.getAttribute('d')),width:svg.getBoundingClientRect().width,height:svg.getBoundingClientRect().height})),
    save:style('[data-test$="save"]'),saveIcon:style('[data-test$="save"] .kel-icon')}
})}
async function assertFocus(page,d){
  await page.keyboard.press('Tab')
  expect(await d.evaluate(node=>node.contains(node.getRootNode().activeElement))).toBe(true)
  for(let i=0;i<12;i++)await page.keyboard.press('Tab')
  expect(await d.evaluate(node=>node.contains(node.getRootNode().activeElement))).toBe(true)
  for(let i=0;i<12;i++)await page.keyboard.press('Shift+Tab')
  expect(await d.evaluate(node=>node.contains(node.getRootNode().activeElement))).toBe(true)
}
async function geometry(locator,width){const box=await locator.boundingBox();expect(box).toBeTruthy();expect(box.x).toBeGreaterThanOrEqual(-1);expect(box.x+box.width).toBeLessThanOrEqual(width+1);return box}
for(const row of cases)test(row.id,async({page,context},info)=>{
  const unexpected=[]
  page.on('pageerror',error=>unexpected.push('page: '+error.stack))
  page.on('console',message=>{if(message.type()==='error'||message.type()==='warning')unexpected.push('console '+message.type()+': '+message.text())})
  page.on('requestfailed',request=>unexpected.push('request: '+request.url()+' '+request.failure()?.errorText))
  page.on('response',response=>{if(response.status()>=400)unexpected.push('response: '+response.status()+' '+response.url())})
  try {
    const behavior=row.behavior,match=/^drawer-(group|user|permission|subject)-(.+)$/.exec(behavior)
    if(match){
      const [,kind,action]=match;await boot(page,row,{path:paths[kind]});
      if(action==='draft-backdrop-history'){await page.evaluate(()=>window.fixture.navigate('application-groups/team'));await expect(by(page,'group-detail')).toBeVisible();await page.evaluate(path=>window.fixture.navigate(path),paths[kind]);await expect(by(page,'group-detail')).toBeHidden();await expect(by(page,'access-control-root')).toHaveAttribute('aria-busy','false')}
      const trigger=await openDrawer(page,kind),d=drawer(page,kind)
      await expect(d.locator('[data-test=drawer-save]')).toHaveText(await text(page,'save'))
      await expect.poll(async()=>{const box=await d.boundingBox();return box ? box.x+box.width : Infinity}).toBeLessThanOrEqual(row.width+1)
      await geometry(d,row.width)
      if(action==='native-style'){
        const actual=await styleSnapshot(d)
        await page.evaluate(()=>window.fixture.reference())
        const reference=page.locator('#reference').locator('[data-test=native-reference]');await expect(reference).toBeVisible()
        const expected=await styleSnapshot(reference)
        expect(actual).toEqual(expected)
        await info.attach('native-style-comparison',{body:JSON.stringify({actual,expected},null,2),contentType:'application/json'})
        await page.evaluate(()=>window.fixture.reference(false))
        await expect(d.locator('form')).toHaveClass(/ks-horizontal/)
        const save=d.locator('[data-test=drawer-save]');expect(await save.getAttribute('form')).toBe(await d.locator('form').getAttribute('id'))
        await expect(save.locator('svg')).toBeVisible()
      }else if(action==='focus-close-reopen'){
        await assertFocus(page,d);await page.keyboard.press('Escape');await expect(d).toBeHidden()
        await expect(trigger).toBeFocused();await trigger.press('Enter');await expect(d).toBeVisible()
        await d.locator('.kel-drawer__close-btn').click();await expect(d).toBeHidden();await expect(trigger).toBeFocused()
      }else if(action==='draft-backdrop-history'){
        await dirty(page,kind)
        await d.locator('.kel-drawer__close-btn').click();const confirm=by(page,'discard-confirm');await expect(confirm).toBeVisible()
        await confirm.getByRole('button',{name:await text(page,'cancel'),exact:true}).click();await expect(confirm).toBeHidden();await expect(d).toBeVisible()
        await page.goBack();await expect(confirm).toBeVisible()
        await page.keyboard.press('Escape');await expect(confirm).toBeHidden();await expect(d).toBeVisible()
        // Native backdrop, using its actual rendered geometry rather than a synthetic DOM event.
        const box=await d.boundingBox();if(box.x>2)await page.mouse.click(1,450);else await d.locator('.kel-drawer__close-btn').click()
        await expect(confirm).toBeVisible();await confirm.getByRole('button',{name:await text(page,'discard'),exact:true}).click();await expect(d).toBeHidden()
        await trigger.click();await expect(d).toBeVisible()
        if(kind!=='subject')await expect(d.locator('input').first()).toHaveValue('')
      }else{
        await fillValid(page,kind);const before=await d.locator('input').evaluateAll(nodes=>nodes.map(node=>node.value))
        const maximize=d.locator('.kel-drawer__header button').first();await maximize.click();await expect(d).toHaveClass(/full-screen/);await maximize.click();await expect(d).not.toHaveClass(/full-screen/)
        await page.evaluate(()=>window.fixture.holdWrites());await d.locator('[data-test=drawer-save]').click()
        await expect(d).toHaveAttribute('aria-busy','true');await expect(d.locator('[data-test=drawer-save]')).toBeDisabled();await expect(d.locator('.kel-drawer__close-btn')).toHaveCount(0)
        await expect(d.locator('[data-test=drawer-save]')).toHaveText(await text(page,'loading'))
        await maximize.click();await expect(d).toHaveClass(/full-screen/)
        expect(await d.locator('input').evaluateAll(nodes=>nodes.map(node=>node.value))).toEqual(before)
        for(const input of await d.locator('input,textarea').all())await expect(input).toBeDisabled()
        await page.keyboard.press('Escape');await expect(d).toBeVisible()
        expect(await page.evaluate(()=>window.fixture.requests.filter(request=>request.method!=='GET').length)).toBe(1)
        await page.evaluate(()=>window.fixture.releaseSave());await expect(d).toBeHidden()
      }
    }else if(behavior==='cards-counts-title'){
      await boot(page,row);const card=by(page,'group-card');await expect(card).toHaveCount(1)
      const metrics=await card.evaluate(node=>{const heading=node.querySelector('.rbac-card-heading'),title=node.querySelector('.application-group-card-title').getBoundingClientRect(),name=node.querySelector('.application-group-card-id').getBoundingClientRect(),count=node.querySelector('.rbac-card-count-label'),bold=count.querySelector('b').getBoundingClientRect(),range=document.createRange();range.selectNodeContents(count.lastChild);const label=range.getBoundingClientRect();return {direction:getComputedStyle(heading).flexDirection,titleBottom:title.bottom,nameTop:name.top,countGap:label.left-bold.right,gap:getComputedStyle(count).columnGap}})
      expect(metrics.direction).toBe('column');expect(metrics.nameTop).toBeGreaterThanOrEqual(metrics.titleBottom);expect(parseFloat(metrics.gap)).toBeGreaterThan(0);expect(metrics.countGap).toBeGreaterThan(0)
      await geometry(card,row.width);await expect(by(page,'group-count-scopes')).toContainText('1')
    }else if(behavior==='list-permission-summary'){
      await boot(page,row,{path:paths.permission});await expect(by(page,'permission-row')).toHaveText('read');const summary=root(page).locator('.rbac-permission-summary');await expect(summary).toContainText('Visible permission summary')
      const expand=summary.getByRole('button');await expect(expand).toBeVisible();await expand.click();await expect(summary).toContainText('Second permission line')
      expect(await root(page).locator('.table-wrap').last().evaluate(node=>node.scrollWidth>=node.clientWidth)).toBe(true)
    }else if(behavior.startsWith('filter-popup')){
      await boot(page,row,{path:'users'});
      if(await root(page).locator('.mobile-toggle').count()){
        const trigger=root(page).locator('.mobile-toggle');await trigger.click();const sheet=root(page).locator('.mobile-sheet');await expect(sheet).toBeVisible()
        const bar=await root(page).locator('.mobile-bar').boundingBox()
        await expect.poll(async()=>{const box=await sheet.boundingBox();return Math.abs(box.y-bar.y-bar.height-8)}).toBeLessThanOrEqual(2)
        await geometry(sheet,row.width);await expect(sheet.locator('.sheet-apply')).toBeVisible()
        await expect(sheet.locator('.sheet-footer-actions button:visible')).toHaveCount(1)
        if(behavior==='filter-popup-buttons-reopen-scroll'){
          await sheet.locator('.field-row-header').first().click();await expect(sheet.locator('.field-row-body')).toBeVisible()
          await sheet.locator('.field-row-body .kel-select__wrapper').last().click()
          const options=root(page).locator('.kel-select-dropdown:visible');await expect(options).toBeVisible();await geometry(options,row.width)
          const scroll=options.locator('.kel-scrollbar__wrap');await expect.poll(()=>scroll.evaluate(node=>Math.abs(node.getBoundingClientRect().height-node.clientHeight))).toBeLessThanOrEqual(1)
          const scrollBox=await scroll.boundingBox();await page.mouse.move(scrollBox.x+20,scrollBox.y+30);await page.mouse.wheel(0,500);await expect.poll(()=>scroll.evaluate(node=>node.scrollTop)).toBeGreaterThan(0)
          await page.keyboard.press('Escape');await expect(options).toBeHidden();await expect(sheet).toBeVisible();await geometry(sheet,row.width)
          await sheet.locator('.sheet-apply').click();await expect(sheet).toBeHidden();await trigger.click();await expect(sheet).toBeVisible();await root(page).locator('.mobile-scrim').click({position:{x:2,y:2}});await expect(sheet).toBeHidden();await trigger.click();await expect(sheet).toBeVisible()
        }
      }else{
      const trigger=root(page).locator('.customize-button');await trigger.click()
      const popup=root(page).locator('.customize-panel:visible, .filter-panel:visible, .kel-popover:visible').last()
      await expect(popup).toBeVisible();const box=await geometry(popup,row.width),anchor=await trigger.boundingBox()
      expect(Math.abs(box.y-(anchor.y+anchor.height))).toBeLessThan(40)
      await expect(popup.locator('.advanced-add')).toBeVisible()
      if(behavior==='filter-popup-buttons-reopen-scroll'){
        await popup.locator('.advanced-add').click();const builder=root(page).locator('.adv-builder');await expect(builder).toBeVisible();await geometry(builder,row.width)
        await page.keyboard.press('Escape');await expect(builder).toBeHidden()
        await trigger.click();await expect(popup).toBeVisible();await page.mouse.wheel(0,500);await geometry(popup,row.width)
        await trigger.click();await expect(popup).toBeHidden();await trigger.click();await expect(popup).toBeVisible()
      }
      }
    }else if(behavior.startsWith('state-')){
      const state=behavior.slice(6).replace('-retry','');await boot(page,row,{state})
      if(state==='normal')await expect(by(page,'group-card')).toBeVisible()
      else if(state==='empty'){await expect(by(page,'group-card')).toHaveCount(0);await expect(root(page)).toContainText(await text(page,'noRows'))}
      else if(state==='loading'){await expect(by(page,'access-control-root')).toHaveAttribute('aria-busy','true');await expect(root(page).getByRole('status')).toHaveText(await text(page,'loading'));await page.evaluate(()=>window.fixture.recover());await expect(by(page,'group-card')).toBeVisible()}
      else {const error=by(page,'request-error');await expect(error).toBeVisible();await expect(error.locator('p')).toHaveText(await text(page,state==='error'?'error':'denied'))
        await expect(by(page,'group-card')).toHaveCount(0)
        if(state==='error'){await page.evaluate(()=>window.fixture.recover());await error.getByRole('button').click();await expect(by(page,'group-card')).toBeVisible()}
        else {await expect(by(page,'sign-in-again')).toHaveText(await text(page,'signInAgain'));await expect(root(page).locator('.management-toolbar')).toHaveCount(0)}
      }
    }else if(behavior==='user-edit-save'){
      await boot(page,row,{path:'users'});await root(page).getByRole('button',{name:await text(page,'edit'),exact:true}).first().click();const d=drawer(page,'user');await expect(d).toBeVisible();await expect(d.locator('output')).toHaveText('alice');await d.locator('[name=name]').fill('Updated fixture');await d.locator('[data-test=drawer-save]').click();await expect(d).toBeHidden();await expect(root(page).locator('.kel-table__body-wrapper')).toContainText('Updated fixture')
    }else if(behavior==='inline-discard'){
      await boot(page,row,{path:'application-groups/team'});await by(page,'group-edit').click();const form=root(page).locator('.rbac-inline-form');await form.locator('textarea').fill('dirty inline');await form.getByRole('button',{name:await text(page,'cancel'),exact:true}).click()
      const confirm=by(page,'discard-confirm').filter({visible:true});await expect(confirm).toBeVisible();await confirm.getByRole('button',{name:await text(page,'cancel'),exact:true}).click();await expect(form.locator('textarea')).toHaveValue('dirty inline');await form.getByRole('button',{name:await text(page,'cancel'),exact:true}).click();await confirm.getByRole('button',{name:await text(page,'discard'),exact:true}).click();await expect(form).toBeHidden()
    }else if(behavior==='credential-copy-retry'){
      await boot(page,row,{path:paths.permission});await by(page,'credential-rotate').click();const dialog=by(page,'credential-once');await expect(dialog).toBeVisible()
      await context.grantPermissions([]);await context.clearPermissions();await context.grantPermissions(['clipboard-read'],{origin:'http://127.0.0.1:4179'})
      // Browser permission denial is real; clipboard, events and components remain native.
      const session=await context.newCDPSession(page);const {targetInfo}=await session.send('Target.getTargetInfo');const browserContextId=targetInfo.browserContextId;await session.send('Browser.setPermission',{browserContextId,permission:{name:'clipboard-write'},setting:'denied',origin:'http://127.0.0.1:4179'})
      const copy=dialog.getByRole('button',{name:await text(page,'copyValue'),exact:true});await copy.click();await expect(dialog).toContainText(await text(page,'copyFailed'))
      await context.clearPermissions();await context.grantPermissions(['clipboard-read','clipboard-write']);await copy.click();await expect(dialog).toContainText(await text(page,'copied'));await dialog.getByRole('button',{name:await text(page,'close'),exact:true}).click();await expect(dialog).toBeHidden();await expect.poll(()=>root(page).evaluate(node=>Boolean(node.shadowRoot.activeElement?.isConnected))).toBe(true)
    }else if(behavior==='theme-switch-refresh-system'){
      await boot(page,row);const colors={}
      for(const theme of ['light','dark','dark-2']){await page.evaluate(theme=>window.fixture.theme(theme),theme);colors[theme]=await root(page).locator('.rbac-page').evaluate(node=>{const css=getComputedStyle(node);return {background:css.backgroundColor,color:css.color}})}
      expect(new Set(Object.values(colors).map(value=>value.background)).size).toBe(3)
      await page.evaluate(()=>window.fixture.theme('system'));await page.emulateMedia({colorScheme:'dark'});await expect.poll(()=>page.evaluate(()=>window.fixture.snapshot().effective)).toBe('dark')
      await expect.poll(()=>root(page).locator('.rbac-page').evaluate(node=>getComputedStyle(node).backgroundColor)).toBe(colors.dark.background)
      await page.reload();await page.waitForFunction(()=>window.fixture?.ready);await expect(by(page,'group-card')).toBeVisible()
      const snapshot=await page.evaluate(()=>window.fixture.snapshot());expect(snapshot.theme).toBe(row.theme)
      expect(await root(page).locator('.rbac-page').evaluate(node=>getComputedStyle(node).backgroundColor)).toBe(colors[snapshot.effective].background)
      await info.attach('theme-colors',{body:JSON.stringify(colors),contentType:'application/json'})
    }
    await expect(root(page)).not.toContainText(/\{\w+\}|undefined|\[object Object\]/)
    if(row.behavior.startsWith('state-') || row.behavior.endsWith('native-style'))await info.attach('rendered-page',{body:await page.screenshot({fullPage:true}),contentType:'image/png'})
  }finally{expect(unexpected,'Unknown console/page/request errors').toEqual([])}
})
