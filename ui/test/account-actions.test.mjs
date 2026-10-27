import test from 'node:test'
import assert from 'node:assert/strict'
import {reactive} from 'vue'
import {createAccount, rotateCredential} from '../src/account-actions.js'
import {newOneTimeValue, clearOneTimeValue} from '../src/credential-state.js'
test('N5/T5: account creation reveals only in live one-time state and returns a sanitized receipt', async () => {
  const state=reactive(newOneTimeValue()), draft=reactive({username:'test-account',name:'',email:''})
  let calls=0
  const api={createUser:async value=>{calls++;assert.deepEqual(value,{username:'test-account',name:'',email:''});return {subject:'test-account',revision:0,temporaryPassword:'isolated-test-value'}}}
  assert.deepEqual(await createAccount(api,draft,state),{subject:'test-account',revision:0})
  assert.equal(Boolean(state.value),true);assert.equal(state.kind,'password');assert.equal(calls,1)
  clearOneTimeValue(state);await createAccount(api,draft,state,()=>false);assert.equal(state.value,'')
})
test('N5/N19/T5: interrupted/invalid account response never supplies a default password or retries', async () => {
  const state=newOneTimeValue();let calls=0
  await assert.rejects(createAccount({createUser:async()=>{calls++;throw {status:503}}},{username:'test-account'},state))
  assert.equal(calls,1);assert.equal(state.value,'')
  for(const receipt of [{subject:'other',revision:1,temporaryPassword:'isolated-test-value'},{subject:'test-account',revision:1},{subject:'test-account',revision:-1,temporaryPassword:'isolated-test-value'}]) await assert.rejects(createAccount({createUser:async()=>receipt},{username:'test-account'},state),/invalid_account_receipt/)
  assert.equal(state.value,'')
})
test('N11/T7/T8: rotation validates target/version and never calls online API for public/maintenance', async () => {
  const state=newOneTimeValue(), credential={clientId:'test-client',clientType:'confidential',rotationMode:'online',credentialRevision:3}
  let calls=0
  const api={rotateCredentials:async(group,client,revision)=>{calls++;assert.equal(group,'g');assert.equal(client,'test-client');assert.equal(revision,3);return {clientId:client,credentialRevision:4,clientSecret:'isolated-test-value'}}}
  assert.deepEqual(await rotateCredential(api,'g',credential,state),{clientId:'test-client',credentialRevision:4});assert.equal(state.kind,'secret')
  clearOneTimeValue(state)
  for(const change of [{clientType:'public'},{rotationMode:'deployment-maintenance'}]) await assert.rejects(rotateCredential(api,'g',{...credential,...change},state),/rotation_not_online/)
  assert.equal(calls,1);assert.equal(state.value,'')
  await rotateCredential(api,'g',credential,state,()=>false);assert.equal(state.value,'')
  await assert.rejects(rotateCredential({rotateCredentials:async()=>({clientId:'other',credentialRevision:4,clientSecret:'isolated-test-value'})},'g',credential,state),/invalid_credential_receipt/)
})
