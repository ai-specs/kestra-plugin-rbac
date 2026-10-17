export function matchesQuery(item, decodedParameters, valueFor) {
  const parameters = decodedParameters.filter(parameter => parameter.field !== 'q')
  const groups = new Map()
  const globals = []
  for (const parameter of parameters) {
    const actual = String(valueFor(item, parameter.field) ?? '').toLocaleLowerCase()
    const expected = String(parameter.value ?? '').toLocaleLowerCase()
    const allowed = parameter.operation === 'EQUALS' ? actual === expected : parameter.operation === 'CONTAINS' ? actual.includes(expected) : false
    if (parameter.groupIndex === undefined) globals.push(allowed)
    else {
      if (!groups.has(parameter.groupIndex)) groups.set(parameter.groupIndex, {logical: parameter.wrapperLogical, children: new Map()})
      const group = groups.get(parameter.groupIndex)
      const child = parameter.wrapperChildIndex ?? 0
      if (!group.children.has(child)) group.children.set(child, [])
      group.children.get(child).push(allowed)
    }
  }
  const combine = (values, logical) => logical === 'OR' ? values.some(Boolean) : values.every(Boolean)
  const grouped = [...groups.values()].map(group => combine([...group.children.values()].map(values => values.every(Boolean)), group.logical))
  return globals.every(Boolean) && (!grouped.length || combine(grouped, parameters.find(parameter => parameter.topLogical)?.topLogical))
}
