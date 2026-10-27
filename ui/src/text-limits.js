export const textLimits = Object.freeze({name: 256, description: 4096, details: 16384})
export const withinTextLimit = (value, maximum, required = false) => typeof value === 'string' && (!required || Boolean(value.trim())) && Array.from(value).length <= maximum
export function groupMetadataInput(value) {
  if (!withinTextLimit(value.displayName, textLimits.name, true) || !withinTextLimit(value.description, textLimits.description)) throw Object.assign(new Error('invalid_group_metadata'), {code: 'invalid_group_metadata', status: 400})
  return {displayName: value.displayName, description: value.description}
}
