export type FieldErrors<K extends string> = Partial<Record<K, string>>;

export function hasErrors<K extends string>(errors: FieldErrors<K>): boolean {
  return Object.values(errors).some(Boolean);
}

/**
 * Moves keyboard focus to the first field with an error, so keyboard and screen-reader users land
 * on the problem. `fieldIds` lists the fields' element ids in page order.
 */
export function focusFirstError<K extends string>(
  errors: FieldErrors<K>,
  fieldIds: Record<K, string>,
): void {
  const first = (Object.keys(fieldIds) as K[]).find((key) => errors[key]);
  if (first) document.getElementById(fieldIds[first])?.focus();
}
