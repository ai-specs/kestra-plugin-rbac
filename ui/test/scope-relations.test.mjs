import test from 'node:test'
import assert from 'node:assert/strict'
import {withAccountState, scopeRelations, blockingReferences, scopeReferenceLabels} from '../src/scope-relations.js'
test('T2: scope conflicts consume typed permission/subject references and reject malformed shapes', () => {
  assert.deepEqual(scopeReferenceLabels([
    {kind:'permission',clientId:'client',permissionKey:'permission',issuer:null,subject:null},
    {kind:'subject',clientId:null,permissionKey:null,issuer:'https://issuer.example',subject:'account'},
    {kind:'permission',clientId:'client',permissionKey:'p',subject:'mixed'},
    {kind:'subject',subject:'missing-issuer'}, {kind:'unknown'}, 'untyped', null
  ]), ['client: permission','subject: account'])
  assert.deepEqual(scopeReferenceLabels(null), [])
})
import {involvedApplications} from '../src/access-control-model.js'
test('N15/T4/T5: grant DTO without active joins exact subject directory; disabled and missing accounts cannot involve apps', () => {
  const grants=[{subject:'a',scopeKeys:['s']},{subject:'b',scopeKeys:['s']},{subject:'c',scopeKeys:[]}]
  const rows=withAccountState(grants,[{subject:'a',active:false},{subject:'b',active:true}])
  assert.deepEqual(rows.map(row=>row.active),[false,true,false]);assert.equal(grants[0].active,undefined)
  const apps=[{clientId:'app',permissions:[{scopeKeys:['s']}]}]
  assert.deepEqual(involvedApplications(rows[0],apps),[]);assert.equal(involvedApplications(rows[1],apps).length,1)
})
test('N7/N9/T2: scope associations carry exact permission tags and blocking subjects including disabled', () => {
  const apps=[{clientId:'external',supportedScopeKeys:['s'],permissions:[{key:'p2',scopeKeys:['other']},{key:'p1',scopeKeys:['s','other']}]},{clientId:'restricted',supportedScopeKeys:['s']}]
  const grants=[{subject:'disabled',active:false,scopeKeys:['s']},{subject:'zero',scopeKeys:[]}]
  const result=scopeRelations('s',apps,grants)
  assert.deepEqual(result.applications.map(app=>[app.clientId,app.permissions.map(p=>p.key)]),[['external',['p1']],['restricted',[]]])
  assert.deepEqual(result.subjects.map(row=>row.subject),['disabled'])
  assert.deepEqual(blockingReferences('s',apps,grants),['external: p1','subject: disabled'])
  assert.deepEqual(blockingReferences('unused',apps,grants),[])
})
