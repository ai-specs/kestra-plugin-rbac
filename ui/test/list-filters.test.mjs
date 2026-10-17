import test from 'node:test'
import assert from 'node:assert/strict'
import {matchesQuery} from '../src/list-filters.js'

const valueFor = (item, field) => item[field]
test('filter conjunction combines fields without weakening equals', () => {
  const filters = [{field: 'name', operation: 'EQUALS', value: 'reader'}, {field: 'description', operation: 'CONTAINS', value: 'team'}]
  assert.equal(matchesQuery({name: 'reader', description: 'Team A'}, filters, valueFor), true)
  assert.equal(matchesQuery({name: 'reader-other', description: 'Team A'}, filters, valueFor), false)
  assert.equal(matchesQuery({name: 'reader', description: 'Other'}, filters, valueFor), false)
})
test('advanced groups retain OR between groups and AND within each leaf', () => {
  const filters = [{field: 'name', operation: 'EQUALS', value: 'reader', groupIndex: 0, topLogical: 'OR'}, {field: 'description', operation: 'EQUALS', value: 'A', groupIndex: 0, topLogical: 'OR'}, {field: 'name', operation: 'EQUALS', value: 'writer', groupIndex: 1, topLogical: 'OR'}]
  assert.equal(matchesQuery({name: 'reader', description: 'A'}, filters, valueFor), true)
  assert.equal(matchesQuery({name: 'reader', description: 'B'}, filters, valueFor), false)
  assert.equal(matchesQuery({name: 'writer', description: 'B'}, filters, valueFor), true)
})
test('unsupported comparators fail closed', () => {
  assert.equal(matchesQuery({name: 'reader'}, [{field: 'name', operation: 'UNKNOWN', value: 'reader'}], valueFor), false)
})
